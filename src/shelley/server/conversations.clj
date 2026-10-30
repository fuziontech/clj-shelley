(ns shelley.server.conversations
  "Every endpoint scoped to a conversation: reading it, talking in it, and
  changing it.

  The rules that hold across all of them:

  - an unknown id is a 404 with a JSON body, never a stack trace;
  - a write that the UI may retry (chat, cancel, archive) is idempotent or
    says clearly why it was refused;
  - anything that changes the list calls `runtime/notify-list-changed!` so the
    unified stream patches itself."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [shelley.compact :as compact]
            [shelley.hooks :as user-hooks]
            [shelley.db :as db]
            [shelley.server.http :as http]
            [shelley.server.models :as models]
            [shelley.server.btw-api :as btw-api]
            [shelley.server.rows :as rows]
            [shelley.server.runtime :as runtime]
            [clojure.tools.logging :as log]
            [shelley.server.sse :as sse]
            [shelley.tools :as tools]
            [shelley.tools.message-user :as message-user])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.util.concurrent TimeUnit)))

;; ---------------------------------------------------------------------------
;; Shared helpers

;; Defined below their first use: a hook is consulted while creating a
;; conversation, and the helpers live next to the chat handlers.
(declare message-hooks hook-headers)


(defn- path-conv [req]
  (db/get-conversation (http/path-param req :id)))

(defn- no-conversation [] (http/not-found "conversation not found"))

(defn- options-of [conv]
  (let [o (:options conv)]
    (if (map? o) o (or (http/parse-json-object (:conversation_options conv)) {}))))

(defn- option-value [conv k]
  (let [o (options-of conv)]
    (or (get o k) (get o (keyword (str/replace (name k) "_" "-"))))))

(defn- tool-list
  "The tools this conversation may use. A profile or the settings panel can
  turn individual tools off or everything off; the model then simply does not
  see them, which is the only enforcement that matters."
  [conv]
  (let [all (tools/tool-defs :default)]
    (cond
      (http/truthy? (option-value conv :disable_all_tools)) []
      (seq (option-value conv :tool_overrides))
      (let [overrides (into {} (map (fn [[k v]] [(name k) (name v)])
                                    (option-value conv :tool_overrides)))]
        (filterv (fn [t] (not= "off" (get overrides (str (:name t))))) all))
      :else all)))

(defn- run-opts [conv]
  (let [thinking (or (option-value conv :thinking_level) (option-value conv :thinking-level))
        extra (option-value conv :system_prompt)]
    {:toolset :default
     :tools (tool-list conv)
     :thinking (when (and thinking (not= "" (str thinking))) (keyword (str thinking)))
     :system-extra extra}))

(defn- row [conv] (rows/conversation-row conv))

(defn- conversation-json
  "One conversation plus the runtime state a client needs to render it."
  [conv]
  (let [id (:id conv)]
    {"conversation" (row conv)
     "agent_working" (runtime/running? id)
     "running" (runtime/running? id)
     "queued_messages" (mapv (fn [q] {"id" (:id q) "text" (:text q) "created_at" (:created_at q)})
                             (runtime/queued-messages id))
     "context_window_size" (models/context-window (:model conv))}))

(defn- valid-dir? [path]
  (and (string? path)
       (str/starts-with? path "/")
       (.isDirectory (java.io.File. path))))

(defn- sanitize-slug
  "Slugs appear in URLs, so they are lower-case words joined by hyphens. An
  input with nothing usable in it is refused rather than stored empty."
  [s]
  (let [s (-> (str s)
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"-+" "-")
              (str/replace #"^-|-$" ""))]
    (when-not (str/blank? s) (subs s 0 (min 80 (count s))))))

(defn- notify! [] (runtime/notify-list-changed!))

;; ---------------------------------------------------------------------------
;; Listing

(defn handler-list
  "GET /api/conversations — limit, offset, q, archived, include-subagents.

  `q` searches message text (and slugs); without it the list is ordered by
  recent activity. `archived=all` returns both halves, which is what the
  archive toggle needs to show a count without two requests."
  [req]
  (let [limit (min 500 (or (http/int-param req :limit 50) 50))
        offset (max 0 (or (http/int-param req :offset 0) 0))
        q (let [v (:q (http/query-params req))] (when-not (str/blank? (str v)) v))
        archived-param (:archived (http/query-params req))
        archived (cond (nil? archived-param) false
                       (contains? #{"all" "*"} (str/lower-case (str archived-param))) :all
                       :else (http/truthy? archived-param))
        include-subagents (http/bool-param req :include-subagents
                                           (http/bool-param req :include_subagents true))
        opts (cond-> {:limit limit :offset offset :include-subagents include-subagents}
               (not= :all archived) (assoc :archived (boolean archived)))
        convs (cond
                q (db/search-conversations q {:limit limit :offset offset
                                              :search-messages? true
                                              :archived (when (not= :all archived) (boolean archived))})
                (= :all archived) (->> (concat (db/list-conversations (assoc opts :archived false))
                                               (db/list-conversations (assoc opts :archived true)))
                                       (sort-by :updated_at)
                                       reverse
                                       (take limit)
                                       vec)
                :else (db/list-conversations opts))]
    (http/ok {"conversations" (mapv (fn [c]
                                      (assoc (row c)
                                             "snippet" (:snippet c)
                                             "snippet_message_id" (:snippet-message-id c)))
                                    convs)
              "limit" limit
              "offset" offset
              "total" (if (and (nil? q) (= :all archived))
                        (+ (db/count-conversations)
                           (count (db/list-conversations {:archived true :limit 1000})))
                        (if q (count convs) (db/count-conversations)))
              "query" q})))

(defn handler-archived [_]
  (http/ok {"conversations" (mapv row (db/list-conversations {:archived true
                                                             :limit 500
                                                             :include-subagents true}))}))

(defn handler-search
  "GET /api/search?q= — conversations and messages in one response."
  [req]
  (let [q (:q (http/query-params req))]
    (if (str/blank? (str q))
      (http/bad-request "q is required")
      (http/ok {"query" q
                "conversations" (mapv (fn [c] (assoc (row c)
                                                     "snippet" (:snippet c)
                                                     "snippet_message_id" (:snippet-message-id c)))
                                      (db/search-conversations q {:search-messages? true :limit 50}))
                "messages" (mapv (fn [m] {"conversation_id" (:conversation-id m)
                                          "message_id" (:message-id m)
                                          "sequence_id" (:sequence-id m)
                                          "type" (name (or (:type m) :user))
                                          "snippet" (:snippet m)
                                          "created_at" (http/iso-time (:created-at m))})
                                 (db/search-messages q {:limit 100}))}))))

;; ---------------------------------------------------------------------------
;; Creating

(defn- normalise-options
  "Conversation options are a small closed vocabulary. Anything else is dropped
  rather than stored, so a typo in a client cannot sit in the row forever."
  [opts]
  (when opts
    (let [o (if (map? opts) opts (or (http/parse-json-object opts) {}))]
      (reduce-kv
       (fn [acc k v]
         (let [k (keyword (str/replace (name k) "-" "_"))]
           (case k
             (:model :cwd :system_prompt :profile) (if (some? v) (assoc acc k v) acc)
             :thinking_level (assoc acc k (keyword (str v)))
             :tool_overrides (assoc acc k (into {} (map (fn [[n s]] [(name n) (name s)])) (or v {})))
             :disable_all_tools (assoc acc k (http/truthy? v))
             acc)))
       {} o))))

(defn- create!
  "Create a conversation row from a create request. Shared by /new and /draft."
  [body & {:keys [draft?]}]
  (let [opts (normalise-options (:conversation_options body))
        cwd (or (:cwd body) (:cwd opts))
        model (or (:model body) (:model opts) (models/default-model-id))]
    (if (and cwd (not (valid-dir? cwd)))
      (throw (ex-info (str "not a directory: " cwd) {:type :bad-request}))
      (let [conv (try (if draft?
                   (db/create-draft-conversation! {:draft (or (:draft body) (:message body) "")
                                                   :cwd cwd :model model :options opts})
                   (db/create-conversation! {:model model
                                             :cwd cwd
                                             :options opts
                                             :title (:title body)
                                             :slug (:slug body)}))
                 ;; A slug a hook asked for may already be taken. That is not a
                 ;; client error — upstream falls back to its own slug, and a
                 ;; 500 here would make the hook unusable more than once.
                 (catch Exception e
                   (if (and (:slug-taken (ex-data e)) (:slug body))
                     (do (log/info "slug" (:slug body) "already taken; generating one")
                         (db/create-conversation! {:model model
                                                   :cwd cwd
                                                   :options opts
                                                   :title (:title body)}))
                     (throw e))))]
        (runtime/conv-state (:id conv))
        (notify!)
        conv))))

(defn- new-conversation-hook
  "Let the user's new-conversation hook shape a conversation before it exists:
  it may rewrite the first prompt, the model, the working directory, and the
  slug. Anything else it returns is ignored, as documented upstream."
  [body req]
  (if-not (user-hooks/available? "new-conversation")
    body
    (let [prompt (str (or (:message body) (:text body) (:content body) ""))
          in {:prompt prompt
              :model (:model body)
              :cwd (:cwd body)
              :readonly {:conversation_id ""
                         :is_subagent false
                         :headers (hook-headers req)}}
          out (try (user-hooks/new-conversation-hook in)
                   (catch Exception e
                     (throw (ex-info (str "new-conversation hook: " (.getMessage e))
                                     {:type :bad-request}))))]
      (cond-> body
        (not-empty (str (:prompt out))) (assoc :message (str (:prompt out)))
        (not-empty (str (:model out))) (assoc :model (str (:model out)))
        (not-empty (str (:cwd out))) (assoc :cwd (str (:cwd out)))
        (not-empty (str (:slug out))) (assoc :slug (str (:slug out)))))))

(defn handler-new
  "POST /api/conversations/new — create and, if a message came with it, start
  the first turn. The turn runs off the request thread; the client watches the
  stream."
  [req]
  (let [body (http/parse-body req)
        raw (or (:message body) (:text body) (:content body))
        ;; A slash command is the user's own command line: it is read from the
        ;; raw message, before any hook has had a chance to rewrite it.
        slash (when (and raw (not (str/blank? (str raw))))
                (user-hooks/slash-command-hook {:message (str raw) :is-new-conversation true
                                                :cwd (:cwd body) :model (:model body)}))
        body (if slash body (new-conversation-hook body req))
        message (or (:message body) (:text body) (:content body))]
    (if (and message (str/blank? (str message)))
      (http/bad-request "message must not be blank")
      (let [conv (create! body)]
        (when (and message (not (str/blank? (str message))))
          (let [hooked (cond
                         (nil? slash) (message-hooks conv (str message)
                                                     {:is-new? true :headers (hook-headers req)})
                         (:error slash) {:action :error :error (:error slash)}
                         (str/blank? (str (:message slash)))
                         {:action :drop :command (:command slash)}
                         :else {:action :send :text (str (:message slash))})]
            (case (:action hooked)
              :error (log/warn "a hook refused the first message:" (:error hooked))
              :drop (log/info "slash command handled the first message; no turn started")
              :send (when-let [t (:text hooked)]
                      (runtime/start-turn! conv t (run-opts conv))))))
        (http/created (conversation-json (db/get-conversation (:id conv))))))))

(defn handler-draft
  "POST /api/conversations/draft — a conversation that exists but has no
  messages yet. Drafts appear in the list; the first chat promotes one."
  [req]
  (http/created (conversation-json (create! (http/parse-body req) :draft? true))))

(defn handler-update-draft
  "PUT /api/conversation/:id/draft — update the draft text, model, or cwd
  before it is promoted. Once promoted there is no draft to update, which is a
  404 rather than a silent edit of a live conversation."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (not (:is-draft conv)) (http/not-found "conversation is no longer a draft")
      :else
      (let [body (http/parse-body req)
            cwd (:cwd body)]
        (when (and cwd (not (valid-dir? cwd)))
          (throw (ex-info (str "not a directory: " cwd) {:type :bad-request})))
        (db/update-draft! (:id conv) (str (or (:draft body) (:message body) (:draft conv))))
        (when (:model body) (db/set-model! (:id conv) (:model body)))
        (when cwd (db/update-cwd! (:id conv) cwd))
        (when (contains? body :conversation_options)
          (db/update-conversation-options! (:id conv)
                                           (normalise-options (:conversation_options body))))
        (notify!)
        (http/ok (conversation-json (db/get-conversation (:id conv))))))))

;; ---------------------------------------------------------------------------
;; Reading one

(defn handler-get
  "GET /api/conversation/:id — the row and its whole transcript.

  `?last_sequence_id=` returns only what a client is missing, which is how a
  reconnecting tab repairs itself without re-reading a long conversation."
  [req]
  (let [conv (path-conv req)
        since (http/int-param req :last_sequence_id)]
    (if (nil? conv)
      (no-conversation)
      (let [id (:id conv)
            messages (if (some? since)
                       (db/messages-since id since)
                       (db/transcript id))]
        (http/ok (merge (conversation-json conv)
                        {"messages" (rows/api-messages messages)
                         "max_sequence_id" (or (:sequence-id (last messages)) 0)
                         "pending_questions" (vec (message-user/pending-question-ids))}))))))

(defn handler-by-slug
  "GET /api/conversation-by-slug/:slug — slug first, then id, so a draft URL
  built from an id resolves before the conversation has a slug."
  [req]
  (let [slug (http/path-param req :slug)
        conv (or (when-not (str/blank? (str slug)) (db/get-conversation-by-slug slug))
                 (db/get-conversation slug))]
    (if (nil? conv) (no-conversation) (http/ok (conversation-json conv)))))

;; ---------------------------------------------------------------------------
;; Talking

(def ^:private secret-headers
  "Headers a user hook must never see. Hooks are scripts on the filesystem; the
  session cookie is not their business."
  #{"cookie" "set-cookie" "authorization" "proxy-authorization"})

(defn- hook-headers
  "Request headers as sorted [name value] pairs, minus the ones carrying secrets."
  [req]
  (->> (:headers req)
       (remove (fn [[k _]] (secret-headers (str/lower-case (name k)))))
       (mapcat (fn [[k v]] (for [one (if (sequential? v) v [v])] [(name k) (str one)])))
       (sort-by first)
       vec))

(defn- message-hooks
  "Run the user's hooks over an incoming user message.

  Two hooks sit between the client and the model. A slash command that has a
  hook file owns the message: its output replaces it, and no output means the
  turn does not run at all. The chat-message hook may rewrite the text. Returns
  {:action :drop}, {:action :send :text t} or {:action :error msg}.

  The first message of a conversation goes through the new-conversation hook
  instead, which is why `is-new?` is passed down rather than guessed."
  [conv text {:keys [is-new? queued? headers]}]
  (let [ctx {:conversation-id (:id conv)
             :is-new-conversation is-new?
             :cwd (:cwd conv)
             :model (:model conv)}]
    ;; A conversation's first message is the new-conversation hook's business,
    ;; not chat-message's — the two would otherwise both rewrite it.
    (if-let [slash (user-hooks/slash-command-hook (assoc ctx :message text))]
      (cond
        (:error slash) {:action :error :error (:error slash)}
        (str/blank? (str (:message slash))) {:action :drop :command (:command slash)}
        :else {:action :send :text (str (:message slash))})
      (if is-new?
        {:action :send :text text}
        (try
        (let [out (user-hooks/chat-message-hook
                   {:message text
                    :readonly {:conversation_id (:id conv)
                               :model (:model conv)
                               :queued (boolean queued?)
                               :headers (or headers [])}})]
          {:action :send :text (str (or (:message out) text))})
        (catch Exception e
          ;; A broken hook is the user's own script: say so, and do not send a
          ;; message they did not write.
          {:action :error :error (.getMessage e)}))))))

(defn handler-chat
  "POST /api/conversation/:id/chat — `{message}`.

  A turn already in flight means the message is queued and spliced in before
  the next model call; the reply says which happened so the UI can place it."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      :else
      (let [body (http/parse-body req)
            message (or (:message body) (:text body) (:content body))]
        (cond
          (str/blank? (str message)) (http/bad-request "message is required")
          (:archived conv) (http/conflict "conversation is archived")
          :else
          (let [conv (if (:is-draft conv)
                       (do (db/promote-draft! (:id conv))
                           (db/get-conversation (:id conv)))
                       conv)
                _ (when (:model body) (db/set-model! (:id conv) (:model body)))
                _ (when (contains? body :conversation_options)
                    (db/update-conversation-options! (:id conv)
                                                     (normalise-options (:conversation_options body))))
                conv (db/get-conversation (:id conv))
                text (str message)]
            ;; Slash commands and the chat-message hook run here, on the way in,
            ;; so what the model and the transcript see is the final text.
            (let [hooked (message-hooks conv text {:is-new? false
                                                   :queued? (runtime/running? (:id conv))
                                                   :headers (hook-headers req)})]
              (case (:action hooked)
                :error (http/bad-request (:error hooked))
                :drop (http/ok {"status" "handled"
                                "command" (:command hooked)
                                "message" ""})
                :send
                (let [text (:text hooked)]
                  (if (runtime/running? (:id conv))
                    (let [entry (runtime/enqueue! (:id conv) text)]
                      (http/accepted {"status" "queued"
                                      "message" text
                                      "queued_id" (:id entry)}))
                    (do (runtime/start-turn! conv text (run-opts conv))
                        (notify!)
                        (http/accepted {"status" "accepted" "message" text}))))))))))))

(defn handler-cancel
  "POST /api/conversation/:id/cancel — stop the running turn at the next check."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      :else
      (let [st (runtime/conv-state (:id conv))
            was-running? (runtime/running? (:id conv))]
        (reset! (:cancelled? st) true)
        ;; Nothing was running: clear the flag so the next turn is not
        ;; cancelled before it starts.
        (when-not was-running? (reset! (:cancelled? st) false))
        (http/ok {"status" (if was-running? "cancelling" "idle")
                  "conversation_id" (:id conv)})))))

(defn- start-turn-from-history
  "Run a turn with no new user message: retry, resume, continue, or a queued
  message promoted to the head of a new turn."
  [conv text]
  (if (runtime/running? (:id conv))
    (if text
      (do (runtime/enqueue! (:id conv) text) (http/accepted {"status" "queued"}))
      (http/conflict "a turn is already running"))
    (do (runtime/start-turn! conv text (run-opts conv))
        (http/accepted {"status" "accepted"}))))

(defn handler-retry
  "POST /api/conversation/:id/retry — re-run the request that failed.

  The error row stays in the transcript: messages are an append-only log, and
  the model never sees error rows anyway."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      :else
      (let [latest (db/latest-actionable-message (:id conv))
            ud (http/parse-json-object (:user-data latest))]
        (cond
          (nil? latest) (http/not-found "conversation not found or empty")
          (not= :error (:type latest)) (http/accepted {"status" "not_applicable"})
          (and ud (contains? ud :retryable) (not (http/truthy? (:retryable ud))))
          (http/conflict "error is not retryable")
          :else (start-turn-from-history conv nil))))))

(defn handler-resume
  "POST /api/conversation/:id/resume — continue a turn the server interrupted
  (a restart in the middle of one)."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (not (:turn-interrupted conv)) (http/accepted {"status" "not_applicable"})
      :else (let [claimed (db/claim-interrupted-turn! (:id conv))]
              (if claimed
                (start-turn-from-history (db/get-conversation (:id conv)) nil)
                (http/accepted {"status" "not_applicable"}))))))

(defn handler-continue
  "POST /api/conversation/:id/continue — ask a model that refused to carry on.

  The refusal row carries `error_type: refusal` in its user data; a plain
  request error is not something a nudge can fix."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      :else
      (let [latest (db/latest-actionable-message (:id conv))
            ud (http/parse-json-object (:user-data latest))
            body (http/parse-body req)]
        (cond
          (nil? latest) (http/not-found "conversation not found or empty")
          (not= :error (:type latest)) (http/accepted {"status" "not_applicable"})
          (and ud (:error_type ud) (not= "refusal" (name (:error_type ud))))
          (http/conflict "latest error is not a refusal")
          :else
          (let [text (or (:message body) (:text body) "Please continue.")]
            (start-turn-from-history conv (str text))))))))

(defn handler-cancel-queued
  "POST /api/conversation/:id/cancel-queued?queued_id= — drop one or all queued
  messages."
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (let [queued-id (:queued_id (http/query-params req))
            removed (runtime/cancel-queued! (:id conv) queued-id)]
        (http/ok {"status" "ok" "removed" removed
                  "queued_messages" (mapv :text (runtime/queued-messages (:id conv)))})))))

(defn handler-send-queued
  "POST /api/conversation/:id/send-queued?queued_id= — interrupt the running
  turn and run the head of the queue as its own turn.

  Only the head may be sent: promoting a later message would reorder what the
  user typed."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (:archived conv) (http/conflict "conversation is archived")
      :else
      (let [queued-id (:queued_id (http/query-params req))
            queue (runtime/queued-messages (:id conv))]
        (cond
          (str/blank? (str queued-id)) (http/bad-request "queued_id is required")
          (empty? queue) (http/conflict "the queue is empty")
          (not= queued-id (:id (first queue))) (http/conflict "queued_id is not the head of the queue")
          :else
          (let [entry (first queue)
                st (runtime/conv-state (:id conv))]
            (reset! (:queue st) (vec (rest queue)))
            (try (db/remove-queued-messages! (:id conv) [(:id entry)]) (catch Exception _ nil))
            (if (runtime/running? (:id conv))
              (do (reset! (:cancelled? st) true)
                  (reset! (:drain-head st) (:text entry))
                  (http/accepted {"status" "sending"}))
              (do (runtime/start-turn! conv (:text entry) (run-opts conv))
                  (http/accepted {"status" "accepted"})))))))))

(defn handler-retry-queued
  "POST /api/conversation/:id/retry-queued — the original retries a queued
  message that failed to send. Nothing here can fail to send a queue entry, so
  the answer is always that there was nothing to retry."
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (http/ok {"status" "not_applicable"
                "queued_messages" (mapv :text (runtime/queued-messages (:id conv)))}))))

;; ---------------------------------------------------------------------------
;; Changing

(defn handler-archive
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (do
        (db/archive-conversation! (:id conv))
        (notify!)
        (http/ok {"archived" true "conversation" (row (db/get-conversation (:id conv)))})))))

(defn handler-archive-all
  "POST /api/conversations/archive-all — archive every active conversation in
  one write. Idempotent: a second call reports 0 and leaves the rest alone."
  [_]
  (let [n (db/archive-all-conversations!)]
    (notify!)
    (http/ok {"archived" true "count" n})))

(defn handler-unarchive
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (do (db/unarchive-conversation! (:id conv))
          (notify!)
          (http/ok {"archived" false "conversation" (row (db/get-conversation (:id conv)))})))))

(defn handler-set-archived
  "POST /api/conversation/:id/archive with `{archived: false}` unarchives, which
  is what a toggle sends."
  [req]
  (let [body (http/parse-body req)]
    (if (and (contains? body :archived) (not (http/truthy? (:archived body))))
      (handler-unarchive req)
      (handler-archive req))))

(defn handler-delete
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      ;; Deleting something already gone is the outcome the caller wanted.
      (http/ok {"status" "deleted"})
      (do (db/delete-conversation! (:id conv))
          (runtime/forget-conversation! (:id conv))
          (notify!)
          (http/ok {"status" "deleted" "conversation_id" (:id conv)})))))

(defn handler-rename
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      :else
      (let [body (http/parse-body req)
            slug (sanitize-slug (or (:slug body) (:name body) (:title body)))]
        (cond
          (nil? slug) (http/bad-request "slug is required (must contain alphanumeric characters)")
          :else
          (try
            (let [updated (db/set-slug! (:id conv) slug)]
              (notify!)
              (http/ok (conversation-json (db/get-conversation (:id updated)))))
            (catch Exception e
              (if (re-find #"(?i)unique|constraint" (str (.getMessage e)))
                (http/conflict "a conversation with that slug already exists")
                (http/internal-error (.getMessage e))))))))))

(defn handler-tags
  "GET returns the tags; POST takes `{tags}`, or `{add, remove}`, or
  `{tag, action}` — all three shapes are in use."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (= :get (:request-method req)) (http/ok {"tags" (vec (or (:tags conv) []))})
      :else
      (let [body (http/parse-body req)
            current (set (or (:tags conv) []))
            next-tags (cond
                        (contains? body :tags) (distinct (map str (:tags body)))
                        (or (contains? body :add) (contains? body :remove))
                        (let [adds (map str (or (:add body) []))
                              removes (set (map str (or (:remove body) [])))]
                          (vec (concat (remove removes current) adds)))
                        (contains? body :tag)
                        (let [t (str (:tag body))
                              action (str (or (:action body) "add"))]
                          (if (= "add" action) (conj (vec current) t)
                              (vec (remove #{t} current))))
                        :else (vec current))]
        (db/set-tags! (:id conv) next-tags)
        (notify!)
        (http/ok {"tags" (vec next-tags)})))))

(defn handler-cwd
  "POST /api/conversation/:id/cwd — change where the agent works.

  The path must exist: a typo would otherwise strand the conversation in a
  directory that is never created. A running turn is refused rather than raced,
  because its pending tool calls were resolved against the old directory."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (:archived conv) (http/conflict "conversation is archived")
      (runtime/running? (:id conv)) (http/conflict "finish or stop the current turn first")
      :else
      (let [body (http/parse-body req)
            path (or (:cwd body) (:path body) (:directory body))]
        (cond
          (str/blank? (str path)) (http/bad-request "cwd is required")
          (not (valid-dir? path)) (http/bad-request (str "not an existing directory: " path))
          :else
          (do (db/update-cwd! (:id conv) path)
              (reset! (:cwd (runtime/conv-state (:id conv))) path)
              ;; A note in the transcript, because the model's context is not
              ;; re-read: it must be told the ground moved under it.
              (db/append-message! {:conversation-id (:id conv)
                                   :role :user
                                   :content [{:type "text"
                                              :text (str "[working directory changed to " path "]")}]})
              (notify!)
              (http/ok {"cwd" path "conversation" (row (db/get-conversation (:id conv)))})))))))

(defn handler-tags-get [req] (handler-tags (assoc req :request-method :get)))

;; ---------------------------------------------------------------------------
;; Per-conversation settings and options

(defn handler-get-settings [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (http/ok {"conversation_id" (:id conv)
                "model" (:model conv)
                "cwd" (:cwd conv)
                "conversation_options" (options-of conv)
                "thinking_level" (option-value conv :thinking_level)
                "tool_overrides" (option-value conv :tool_overrides)
                "disable_all_tools" (http/truthy? (option-value conv :disable_all_tools))
                "profile" (option-value conv :profile)
                "system_prompt" (option-value conv :system_prompt)
                "available_models" (mapv :id (models/models))}))))

(defn handler-set-settings
  "POST /api/conversation/:id/settings — model, thinking level, cwd, and the
  conversation's own options. `POST .../options` is the same handler: the UI
  uses that name and both are shipped."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      :else
      (let [body (http/parse-body req)
            opts (normalise-options (or (:conversation_options body) body))]
        (when-let [m (or (:model body) (:model opts))] (db/set-model! (:id conv) m))
        (when-let [path (or (:cwd body) (:cwd opts))]
          (if (valid-dir? path)
            (do (db/update-cwd! (:id conv) path)
                (reset! (:cwd (runtime/conv-state (:id conv))) path))
            (throw (ex-info (str "not an existing directory: " path) {:type :bad-request}))))
        (when (seq opts) (db/update-conversation-options! (:id conv) opts))
        (notify!)
        (http/ok (conversation-json (db/get-conversation (:id conv))))))))

;; ---------------------------------------------------------------------------
;; Compaction and generations

(defn handler-compact
  "POST /api/conversation/:id/compact — compact now.

  Compaction here is a generation bump: the summary opens a new generation so
  the old rows stay readable and the model's context starts clean. Doing it in
  place would rewrite history that the transcript already showed the user."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (:archived conv) (http/conflict "conversation is archived")
      (runtime/running? (:id conv)) (http/conflict "finish or stop the current turn first")
      :else
      (let [messages (db/messages (:id conv))]
        (if (<= (count messages) 3)
          (http/ok {"status" "not_applicable" "reason" "conversation is too short to compact"})
          (do
            (runtime/emit-event! (:id conv) {:type :compaction-start :messages (count messages)})
            (let [summary (try
                            (compact/summarize messages {:model (:model conv)})
                            (catch Exception e
                              (str "earlier conversation could not be summarised: "
                                   (.getMessage e))))
                  new-generation (db/increment-generation! (:id conv))]
              (db/append-message! {:conversation-id (:id conv)
                                   :generation new-generation
                                   :role :user
                                   :content [{:type "text"
                                              :text (str "Summary of the earlier conversation:\n\n" summary)}]
                                   :user-data {:compaction true
                                               :compacted_messages (count messages)
                                               :from_generation (:current-generation conv)}})
              (runtime/emit-event! (:id conv)
                                   {:type :compaction-end
                                    :compacted_messages (count messages)
                                    :generation new-generation})
              (notify!)
              (http/ok {"status" "compacted"
                        "generation" new-generation
                        "compacted_messages" (count messages)
                        "conversation" (row (db/get-conversation (:id conv)))}))))))))

(defn handler-new-generation
  "POST /api/conversation/:id/new-generation — start a fresh context window
  without summarising. `/clear` in the CLI is this."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (runtime/running? (:id conv)) (http/conflict "finish or stop the current turn first")
      :else (let [generation (db/increment-generation! (:id conv))]
              (notify!)
              (http/ok (assoc (row (db/get-conversation (:id conv)))
                              "current_generation" generation))))))

(defn handler-enable-compact-in-place
  "POST /api/conversation/:id/enable-compact-in-place — opt this conversation
  into in-place compaction, which the agent may do itself mid-turn."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (:archived conv) (http/conflict "conversation is archived")
      (:is-draft conv) (http/conflict "conversation is still a draft")
      :else (do (db/update-conversation-options! (:id conv) {:compact_in_place true})
                (notify!)
                (http/ok (assoc (row (db/get-conversation (:id conv)))
                                "conversation_options"
                                (http/json-string (options-of (db/get-conversation (:id conv))))))))))

(defn handler-promote
  "POST /api/conversation/:id/promote — turn a draft into a real conversation."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      (not (:is-draft conv)) (http/ok (conversation-json conv))
      :else (do (db/promote-draft! (:id conv))
                (notify!)
                (http/ok (conversation-json (db/get-conversation (:id conv))))))))

;; ---------------------------------------------------------------------------
;; Fork, subagents, status bar

(defn- fork-cutoff
  "Resolve where a fork starts: a message id wins, then a sequence id. Returns
  `[status cutoff]` with status `:ok`, `:no-message`, or `:wrong-conversation`."
  [conv body]
  (let [message-id (or (:message_id body) (:message-id body))
        seq-id (:sequence_id body)]
    (if (str/blank? (str message-id))
      [:ok (when seq-id (long seq-id))]
      (let [m (db/message-by-id message-id)]
        (cond
          (nil? m) [:no-message nil]
          (not= (:conversation-id m) (:id conv)) [:wrong-conversation nil]
          :else [:ok (:sequence-id m)])))))

(defn handler-fork
  "POST /api/conversation/:id/fork — copy up to a message and continue there.

  Body: `{message_id}` or `{sequence_id}`; neither means the whole conversation.
  A fork with no message at or before the cutoff is refused, because a
  conversation with no history is not a fork of anything."
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (let [body (http/parse-body req)
            [status cutoff] (fork-cutoff conv body)
            latest (db/latest-actionable-message (:id conv))]
        (cond
          (= status :no-message) (http/not-found "message not found")
          (= status :wrong-conversation) (http/bad-request "message does not belong to this conversation")
          (nil? latest) (http/bad-request "conversation has no messages to fork")
          :else
          (let [cutoff (long (if (or (nil? cutoff)
                                     (not (pos? (long cutoff)))
                                     (> (long cutoff) (:sequence-id latest)))
                               (:sequence-id latest)
                               cutoff))]
            (try
              (let [fork (db/fork-conversation! {:source-id (:id conv)
                                                 :cutoff-sequence-id cutoff
                                                 :slug (:slug body)
                                                 :title (:title body)})]
                (notify!)
                (http/created (conversation-json fork)))
              (catch Exception e
                (if (re-find #"(?i)invalid fork|no message" (str (.getMessage e)))
                  (http/bad-request "no message at or before the requested cutoff")
                  (http/internal-error (.getMessage e)))))))))))

(defn handler-subagents
  "GET /api/conversation/:id/subagents — the direct children, each with the
  state a tree view needs."
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (http/ok {"conversation_id" (:id conv)
                "subagents" (mapv (fn [c] (assoc (row c) "preview" (:preview c)))
                                  (db/subagents (:id conv)))}))))

(defn- descendant-conversations [conv-id]
  (let [kids (db/subagents conv-id)]
    (into kids (mapcat #(descendant-conversations (:id %)) kids))))

(defn handler-subagent-usage
  "GET /api/conversation/:id/subagent-usage — what the children cost."
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (let [kids (descendant-conversations (:id conv))
            per-model (reduce (fn [acc c]
                                (let [u (models/conversation-usage (:id c))]
                                  (update acc (or (:model c) "unknown")
                                          (fn [cur]
                                            (merge-with + (or cur {:llm_calls 0 :input_tokens 0
                                                                   :output_tokens 0
                                                                   :cache_read_tokens 0
                                                                   :cache_write_tokens 0
                                                                   :cost_usd 0.0})
                                                       (select-keys u [:llm_calls :input_tokens
                                                                       :output_tokens :cache_read_tokens
                                                                       :cache_write_tokens :cost_usd]))))))
                              {}
                              kids)]
        (http/ok {"conversation_id" (:id conv)
                  "subagent_count" (count kids)
                  "llm_calls" (reduce + 0 (map :llm_calls (vals per-model)))
                  "estimated_usd" (reduce + 0.0 (map :cost_usd (vals per-model)))
                  "per_model" (mapv (fn [[m u]] (assoc u "model" m)) per-model)})))))

(def ^:private status-bar-hook (str (System/getProperty "user.home") "/.config/shelley/hooks/status-bar"))

(defn handler-status-bar
  "GET /api/conversation/:id/status-bar — whatever the status-bar hook prints.

  The hook is a program, not a setting: it gets the conversation as JSON on
  stdin and prints `{body, bgcolor, color}`. A machine without the hook, or a
  hook that hangs, yields an empty bar rather than a failed request."
  [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (let [f (java.io.File. status-bar-hook)]
        (if (and (.canExecute f) (not (str/blank? (str (:cwd conv)))))
          (try
            (let [pb (doto (ProcessBuilder. [status-bar-hook])
                       (.directory (java.io.File. (:cwd conv)))
                       (.redirectErrorStream true))
                  proc (.start pb)]
              (with-open [in (java.io.OutputStreamWriter. (.getOutputStream proc) "UTF-8")]
                (.write in (json/generate-string {"conversation_id" (:id conv)
                                                  "slug" (:slug conv)
                                                  "cwd" (:cwd conv)
                                                  "model" (:model conv)})))
              (if (.waitFor proc 2 TimeUnit/SECONDS)
                (let [out (slurp (.getInputStream proc))
                      parsed (try (json/parse-string out true) (catch Exception _ nil))]
                  (if (map? parsed)
                    (http/ok (select-keys parsed [:body :bgcolor :color :error]))
                    (http/ok {"error" (str "status-bar hook printed no JSON: "
                                           (subs out 0 (min 200 (count out))))})))
                (do (.destroyForcibly proc)
                    (http/ok {"error" "status-bar hook timed out"}))))
            (catch Exception e
              (http/ok {"error" (str "status-bar hook failed: " (.getMessage e))})))
          (http/ok {}))))))

;; ---------------------------------------------------------------------------
;; Hooks

(defn handler-hooks-post
  "POST /api/conversation/:id/hooks — register an end-of-turn webhook."
  [req]
  (let [conv (path-conv req)]
    (cond
      (nil? conv) (no-conversation)
      :else
      (let [body (http/parse-body req)
            url (or (:url body) (:callback_url body))]
        (if (str/blank? (str url))
          (http/bad-request "url is required")
          (let [hooks (vec (distinct (conj (vec (or (option-value conv :hooks) [])) (str url))))]
            (db/update-conversation-options! (:id conv) {:hooks hooks})
            (http/ok {"hooks" hooks})))))))

;; ---------------------------------------------------------------------------
;; message_user questions
;;
;; The `message_user` tool can block waiting for a reply. The question travels
;; as an SSE event, and the reply comes back through these endpoints — without
;; them the agent waits out its timeout on a question the user already answered.

(defn- answer-question [req]
  (let [body (http/parse-body req)
        question-id (or (http/path-param req :question_id)
                        (:question_id body) (:question-id body) (:id body))
        text (or (:text body) (:answer body) "")]
    (cond
      (str/blank? (str question-id))
      (http/bad-request "question_id is required")

      (message-user/answer! question-id (str text))
      (http/ok {"answered" true "question_id" (str question-id)})

      :else
      (http/not-found "no pending question with that id (already answered or timed out)"))))

(defn handler-answer-question [req] (answer-question req))

(defn handler-pending-questions [req]
  (let [conv (if (http/path-param req :id) (path-conv req) ::global)]
    (if (nil? conv)
      (no-conversation)
      (http/ok (cond-> {"question_ids" (vec (message-user/pending-question-ids))}
                 (not= ::global conv) (assoc "conversation_id" (:id conv)))))))

(defn handler-hooks-get [req]
  (let [conv (path-conv req)]
    (if (nil? conv)
      (no-conversation)
      (http/ok {"hooks" (vec (or (option-value conv :hooks) []))}))))

;; ---------------------------------------------------------------------------
;; "by the way" readers and live voice calls: the original's side channels,
;; which this build does not have. They answer honestly instead of pretending.
;; ---------------------------------------------------------------------------

;; btw side questions live in `shelley.server.btw-api`; these are the aliases
;; that spell the btw id `:id`, which is what the route table of the original
;; uses. The handlers read `:btw_id`, so the alias renames it.
(defn- btw-child-handler [handler]
  (fn [req]
    (handler (assoc-in req [:params :btw_id] (get-in req [:params :id-2])))))

(defn handler-live-stub [req]
  (if (nil? (path-conv req))
    (no-conversation)
    (http/not-implemented "live voice session")))

(defn handler-live-transcript [req]
  (if (nil? (path-conv req))
    (no-conversation)
    (http/not-found "no such live call")))

(def routes
  [["/api/conversations" {:get handler-list :post handler-new}]
   ["/api/conversation" {:post handler-new}]
   ["/api/conversations/create" {:post handler-new}]
   ["/api/conversations/archived" {:get handler-archived}]
   ["/api/conversations/archive-all" {:post handler-archive-all}]
   ["/api/conversations/search" {:get handler-search}]
   ["/api/search" {:get handler-search}]
   ["/api/conversations/new" {:post handler-new}]
   ["/api/conversations/draft" {:post handler-draft}]
   ["/api/conversation-by-slug/:slug" {:get handler-by-slug}]

   ["/api/conversation/:id" {:get handler-get
                             :delete handler-delete}]
   ["/api/conversation/:id/stream" {:get sse/handle-conversation-stream}]
   ["/api/conversation/:id/chat" {:post handler-chat}]
   ["/api/conversation/:id/cancel" {:post handler-cancel}]
   ["/api/conversation/:id/cancel-queued" {:post handler-cancel-queued}]
   ["/api/conversation/:id/send-queued" {:post handler-send-queued}]
   ["/api/conversation/:id/retry-queued" {:post handler-retry-queued}]
   ["/api/conversation/:id/retry" {:post handler-retry}]
   ["/api/conversation/:id/resume" {:post handler-resume}]
   ["/api/conversation/:id/continue" {:post handler-continue}]
   ["/api/conversation/:id/archive" {:post handler-set-archived}]
   ["/api/conversation/:id/unarchive" {:post handler-unarchive}]
   ["/api/conversation/:id/delete" {:post handler-delete}]
   ["/api/conversation/:id/rename" {:post handler-rename}]
   ["/api/conversation/:id/tags" {:get handler-tags-get :post handler-tags}]
   ["/api/conversation/:id/settings" {:get handler-get-settings :post handler-set-settings}]
   ["/api/conversation/:id/options" {:get handler-get-settings :post handler-set-settings}]
   ["/api/conversation/:id/cwd" {:post handler-cwd}]
   ["/api/conversation/:id/change-dir" {:post handler-cwd}]
   ["/api/conversation/:id/compact" {:post handler-compact}]
   ["/api/conversation/:id/promote" {:post handler-promote}]
   ["/api/conversation/:id/draft" {:put handler-update-draft}]
   ["/api/conversation/:id/new-generation" {:post handler-new-generation}]
   ["/api/conversation/:id/enable-compact-in-place" {:post handler-enable-compact-in-place}]
   ["/api/conversation/:id/fork" {:post handler-fork}]
   ["/api/conversation/:id/subagents" {:get handler-subagents}]
   ["/api/conversation/:id/children" {:get handler-subagents}]
   ["/api/conversation/:id/subagent-usage" {:get handler-subagent-usage}]
   ["/api/conversation/:id/status-bar" {:get handler-status-bar}]
   ["/api/conversation/:id/hooks" {:get handler-hooks-get :post handler-hooks-post}]
   ["/api/conversation/:id/message-user/:question_id/answer" {:post handler-answer-question}]
   ["/api/conversation/:id/message-user/pending" {:get handler-pending-questions}]
   ["/api/message-user/answer" {:post handler-answer-question}]
   ["/api/message-user/pending" {:get handler-pending-questions}]
   ["/api/conversation/:id/btw/:id/summarize" {:post (btw-child-handler btw-api/summarize-btw)}]
   ["/api/conversation/:id/btw/:id/dismiss" {:post (btw-child-handler btw-api/dismiss-btw)}]
   ["/api/conversation/:id/live-session" {:post handler-live-stub}]
   ["/api/conversation/:id/live-message" {:post handler-live-stub}]
   ["/api/conversation/:id/live-session/:id/stream" {:get handler-live-stub}]
   ["/api/conversation/:id/live-session/:id/events" {:post handler-live-stub}]
   ["/api/conversation/:id/live-session/:id" {:delete handler-live-stub}]
   ["/api/conversation/:id/call/:id/transcript" {:get handler-live-transcript}]

   ;; Plural aliases: the UI probes both, and older clients only know these.
   ["/api/conversations/:id" {:get handler-get :delete handler-delete}]
   ["/api/conversations/:id/events" {:get sse/handle-conversation-stream}]
   ["/api/conversations/:id/messages" {:post handler-chat}]
   ["/api/conversations/:id/cancel" {:post handler-cancel}]
   ["/api/conversations/:id/archive" {:post handler-set-archived}]
   ["/api/conversations/:id/unarchive" {:post handler-unarchive}]
   ["/api/conversations/:id/tags" {:get handler-tags-get :post handler-tags}]
   ["/api/conversations/:id/change-dir" {:post handler-cwd}]
   ["/api/conversations/:id/compact" {:post handler-compact}]])
