(ns shelley.server.runtime
  "Per-conversation runtime state and the code that runs a turn.

  This used to live in `shelley.server`; it moved out so that the handler
  namespaces can start turns without a require cycle back through the router.
  `shelley.server` re-exports the names it used to own, so nothing outside this
  directory notices.

  Three pieces of state are per conversation and deliberately in memory:

    :running?   whether a turn is in flight right now
    :cancelled? the flag the loop polls between rounds
    :queue      user messages typed mid-turn, drained before the next model
                call so the agent answers them in the same turn

  Everything else — the transcript, the model, the cwd — lives in SQLite, which
  is why a restart loses only what was in flight."
  (:require [shelley.compact :as compact]
            [shelley.server.http :as http-util]
            [shelley.db :as db]
            [shelley.llm :as llm]
            [shelley.loop :as loop]
            [shelley.tools :as tools]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def default-model
  (or (System/getenv "CLJ_SHELLEY_MODEL") "anthropic/claude-sonnet-5-5"))

(def system-prompt
  (str "You are Shelley, a coding agent running on a Linux VM. You and the user share one "
       "working directory.\n\n"
       "Work by acting, not by describing. When asked to do something to the filesystem or a "
       "repository, use bash and patch. Read before you edit. Prefer several small correct "
       "commands over one clever line. When a command fails, read the error and fix the cause "
       "rather than retrying the same thing.\n\n"
       "Report what you actually did and what you verified. If you could not verify something, "
       "say so plainly. Keep chat replies short — the work is the message."))

(def context-limit 200000)

;; ---------------------------------------------------------------------------
;; State

(defonce state
  (atom {:conversations {}}))

(defn new-conv-state [id]
  {:cwd (atom (or (:working_dir (db/get-conversation id)) (System/getProperty "user.dir")))
   :cancelled? (atom false)
   :running? (atom false)
   :queue (atom [])
   :subs (atom #{})
   :seq (atom 0)
   :drain-head (atom nil)
   ;; Messages written into the transcript by something other than the user —
   ;; a background job finishing — that the running loop has not seen yet.
   :inject-buffer (atom [])
   ;; Set when such a message arrived and the loop had already made its last
   ;; model call, so nothing will pick it up until a turn ends.
   :resume-needed? (atom false)
   :turn-lock (Object.)})

(defn conv-state [id]
  (or (get-in @state [:conversations id])
      (let [fresh (new-conv-state id)]
        (swap! state assoc-in [:conversations id] fresh)
        fresh)))

(defn forget-conversation! [id]
  (swap! state update :conversations dissoc id))

(defn running? [id]
  (boolean @(:running? (conv-state id))))

;; ---------------------------------------------------------------------------
;; Event fan-out

(defonce ^{:doc "Channels subscribed to every conversation's events (the unified stream)."}
  global-subs
  (atom #{}))

(defonce ^{:doc "Called after anything that can change the conversation list.
  `shelley.server.sse` installs the patch recompute here; it is an atom rather
  than a require so the runtime does not depend on the stream."}
  list-change-hook
  (atom nil))

(defn notify-list-changed! []
  (when-let [f @list-change-hook]
    (try (f) (catch Exception _ nil))))

(defn sse-frame
  "One SSE record. The event type travels inside the JSON rather than in an
  `event:` line: a named EventSource listener silently drops types it does not
  know, while `onmessage` always fires."
  ([payload] (str "data: " payload "\n\n"))
  ([id payload] (str (when id (str "id: " id "\n")) "data: " payload "\n\n")))

(defn emit-event!
  "Publish one conversation event to every listener: the per-conversation
  streams and the unified stream."
  [conv-id event]
  (let [st (conv-state conv-id)
        payload (json/generate-string (assoc event :conversation_id conv-id))
        id (when-let [n @(:seq st)] (when (pos? (long n)) (str "seq:" n)))
        frame (sse-frame id payload)
        global-frame (sse-frame payload)]
    (doseq [ch @(:subs st)]
      (http/send! ch frame false))
    (doseq [ch @global-subs]
      (http/send! ch global-frame false)))
  nil)

(defn subscribe! [conv-id ch]
  (swap! (:subs (conv-state conv-id)) conj ch))

(defn unsubscribe! [conv-id ch]
  (swap! (:subs (conv-state conv-id)) disj ch))

(defn subscribe-global! [ch] (swap! global-subs conj ch))
(defn unsubscribe-global! [ch] (swap! global-subs disj ch))

(defn broadcast-global!
  "A frame for the unified stream only (list patches, heartbeats)."
  [body]
  (let [frame (sse-frame (json/generate-string body))]
    (doseq [ch @global-subs] (http/send! ch frame false)))
  nil)

(defn run-in-background
  "Run work off the request thread. A future that throws would otherwise vanish
  silently, so log it and surface it to any open stream."
  [conv-id f]
  (future
    (try (f)
         (catch Throwable t
           (binding [*out* *err*] (println "background task failed:" (.getMessage t)))
           (.printStackTrace t)
           (emit-event! conv-id {:type :error :text (str "turn failed: " (.getMessage t))
                                 :retryable false})
           (emit-event! conv-id {:type :turn-end :status :error})))))

;; ---------------------------------------------------------------------------
;; Queue

(defn queued-messages [conv-id]
  @(:queue (conv-state conv-id)))

(defn enqueue!
  "Hold a user message until the running turn can take it. The message is
  written to the database too, so a restart or another viewer still sees it."
  [conv-id text]
  (let [st (conv-state conv-id)
        entry {:id (str (java.util.UUID/randomUUID))
               :text (str text)
               :created_at (http-util/now-iso)}]
    (swap! (:queue st) conj entry)
    (try
      (db/append-queued-message! conv-id
                                 {:id (:id entry)
                                  :text (:text entry)
                                  :llm {:role "user" :content [{:type "text" :text (:text entry)}]}})
      (catch Exception e
        (binding [*out* *err*] (println "could not persist queued message:" (.getMessage e)))))
    (emit-event! conv-id {:type :queued :text (:text entry) :id (:id entry)})
    entry))

(defn cancel-queued!
  "Drop one queued message by id, or all of them when `queued-id` is nil."
  [conv-id queued-id]
  (let [st (conv-state conv-id)
        before @(:queue st)
        kept (if queued-id
               (vec (remove #(= queued-id (:id %)) before))
               [])]
    (reset! (:queue st) kept)
    (try
      (if queued-id
        (db/remove-queued-messages! conv-id [queued-id])
        (db/clear-queued-messages! conv-id))
      (catch Exception _ nil))
    (- (count before) (count kept))))

(defn- take-queued!
  "Remove and return everything queued, oldest first."
  [st]
  (let [pending @(:queue st)]
    (reset! (:queue st) [])
    (vec pending)))

;; ---------------------------------------------------------------------------
;; Running a turn

(declare run-agent-turn last-assistant-text start-turn! inject-user-message! message-text)

(defn- runtime-user-message [text]
  (llm/user-message text))

(defn last-assistant-text
  [messages]
  (->> messages
       (filter #(= :assistant (:role %)))
       last
       :content
       (filter llm/text?)
       (map :text)
       (str/join "\n")))

(defn run-subagent
  "Create a child conversation, run one turn to completion, return its text."
  [{:keys [parent-id slug prompt toolset model]}]
  (let [conv (db/create-subagent-conversation! {:parent-id parent-id
                                                :slug slug
                                                :model (or model default-model)
                                                :cwd (:working_dir (db/get-conversation parent-id))})
        _ (run-agent-turn {:conversation conv
                           :text prompt
                           :toolset (or toolset :default)
                           :record? true})
        msgs (db/messages (:id conv))]
    {:slug slug
     :conversation_id (:id conv)
     :text (last-assistant-text msgs)}))

(defn- persist-user-message!
  [conv-id text queued-id]
  (db/append-message! (cond-> {:conversation-id conv-id
                               :role :user
                               :content (:content (runtime-user-message text))}
                        queued-id (assoc :remove-queued-id queued-id))))

(defn- post-turn-hooks
  "Tell every registered webhook the turn ended. Fire and forget: a hook that
  is slow or gone must not hold the turn open, which is why this runs on its
  own thread with a short timeout."
  [conv status]
  (let [urls (:hooks (:options conv))]
    (when (seq urls)
      (future
        (doseq [url urls]
          (try
            (let [client (-> (java.net.http.HttpClient/newBuilder)
                             (.connectTimeout (java.time.Duration/ofSeconds 2))
                             (.build))
                  body (json/generate-string {"type" "turn-end"
                                              "conversation_id" (:id conv)
                                              "slug" (:slug conv)
                                              "status" (name status)
                                              "at" (http-util/now-iso)})
                  req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str url)))
                          (.timeout (java.time.Duration/ofSeconds 5))
                          (.header "content-type" "application/json")
                          (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                          (.build))]
              (.send client req (java.net.http.HttpResponse$BodyHandlers/discarding)))
            (catch Throwable _ nil)))))))

(defn run-agent-turn
  "Run one turn for a conversation. `:text` is the user message, or nil to
  resume from history (retry, continue, interrupted-turn resume). Blocks until
  the turn ends and returns the loop's result map.

  opts: :conversation (row) :text :tools (override) :toolset :system-extra
        :thinking :record? :queued-id"
  [{:keys [conversation text toolset tools system-extra thinking record? queued-id]}]
  (let [conv-id (:id conversation)
        st (conv-state conv-id)
        _ (reset! (:cancelled? st) false)
        _ (db/set-agent-working! conv-id true)
        _ (when (and text (not= false record?))
            (persist-user-message! conv-id text queued-id))
        history (db/messages conv-id)
        _ (reset! (:seq st) (or (-> history last :sequence-id) 0))
        ctx {:working-dir (:cwd st)
             :conversation-id conv-id
             :emit (fn [e] (emit-event! conv-id e))
             :base-url (llm/base-url)
             :run-subagent run-subagent
             ;; A finished background job arrives here as a message the agent
             ;; did not just produce. It is written to the transcript and then
             ;; gets a turn of its own, so its output is never silent.
             :inject-message (fn [message] (inject-user-message! conv-id message))}
        model (or (:model conversation) default-model)
        options (let [o (:options conversation)]
                  (if (map? o) o (or (http-util/parse-json-object (:conversation_options conversation)) {})))
        thinking-level (or thinking (:thinking_level options) (:reasoning-effort conversation))
        tools (or tools (tools/tool-defs toolset))
        inject (fn []
                 ;; Messages another thread wrote into the transcript since the
                 ;; loop last looked. They are already recorded, so they only
                 ;; need to reach the loop's view of history.
                 (let [injected (vec @(:inject-buffer st))
                       _ (reset! (:inject-buffer st) [])
                       pending (take-queued! st)]
                   (when (or (seq injected) (seq pending))
                     (into (mapv (fn [message] (runtime-user-message (message-text message)))
                                 injected)
                           (mapv (fn [{:keys [id text]}]
                                   ;; Recording here rather than in the loop keeps
                                   ;; the queue entry and the transcript row in
                                   ;; one transaction, so a crash loses neither.
                                   (persist-user-message! conv-id text id)
                                   (emit-event! conv-id {:type :message-user :text text :reply_id id})
                                   (runtime-user-message text))
                                 pending)))))
        result (try
                 (loop/run-turn {:model model
                                 :system (cond-> [system-prompt
                                                  (str "Working directory: " @(:cwd st))]
                                           (not (str/blank? (str system-extra))) (conj (str system-extra)))
                                 :messages history
                                 :tools tools
                                 :ctx ctx
                                 :emit (:emit ctx)
                                 :thinking-level (when thinking-level (keyword (str thinking-level)))
                                 :context-limit context-limit
                                 :compact-fn (fn [msgs]
                                               (compact/compact-history msgs {:model model}))
                                 :cancelled? #(boolean @(:cancelled? st))
                                 :inject inject
                                 :record! (fn [m]
                                            ;; :usage-data, not :usage — the DB
                                            ;; drops unknown keys, so the wrong
                                            ;; name loses every token count and
                                            ;; the UI says "no usage recorded".
                                            (let [row (db/append-message! {:conversation-id conv-id
                                                                           :role (:role m)
                                                                           :content (:content m)
                                                                           :usage-data (:usage m)
                                                                           :model-name model})]
                                              (when-let [s (:sequence-id row)]
                                                (reset! (:seq st) s))
                                              row))
                                 :record-error! (fn [m]
                                                  (db/append-message! {:conversation-id conv-id
                                                                       :type :error
                                                                       :content (:content m)
                                                                       :user-data (assoc (:error m)
                                                                                         :retryable (boolean (-> m :error :retryable)))
                                                                       :bump-timestamp true}))})
                 (finally
                   (db/set-agent-working! conv-id false)))]
    ;; A first message becomes the title; later ones must not rename the
    ;; conversation out from under the user.
    (when (and text (str/blank? (str (:title conversation))))
      (db/touch! conv-id (subs text 0 (min 80 (count text)))))
    (db/touch! conv-id nil)
    (when (not= false record?)
      (post-turn-hooks (or (db/get-conversation conv-id) conversation) (:status result)))
    (notify-list-changed!)
    result))

(defn- drain-and-run
  "After a turn ends, anything still queued (or a send-now request) becomes the
  next turn. Without this a message typed in the second between the model's
  last call and the end of the turn would sit in the queue forever.

  Each queued message gets its own turn rather than being merged into one: they
  were typed as separate thoughts and the agent should answer them separately."
  [conversation opts]
  (let [conv-id (:id conversation)
        st (conv-state conv-id)
        ;; The message that started this run. It is not in the queue — the
        ;; caller handed it over directly — so it has to be the first entry, or
        ;; a chat from an idle conversation would start a turn with nothing in
        ;; it and persist nothing.
        first-text (:text opts)]
    (loop [n 0
           carry first-text]
      (when (< n 20)
        (let [head @(:drain-head st)
              _ (reset! (:drain-head st) nil)
              pending (take-queued! st)
              entry (cond
                      (some? carry) {:text carry :id nil}
                      (seq pending) (first pending)
                      head {:text head :id nil}
                      :else nil)]
          (when entry
            ;; `take-queued!` already emptied the queue; put back what this
            ;; round is not consuming. A carried text consumes none of it.
            (reset! (:queue st) (if (some? carry) (vec pending) (vec (rest pending))))
            (run-agent-turn (assoc opts
                                   :conversation (db/get-conversation conv-id)
                                   :text (:text entry)
                                   :queued-id (:id entry)))
            (recur (inc n) nil)))))))

(defn- message-text
  "The text of a message built by a tool (content is a vector of blocks)."
  [message]
  (->> (:content message)
       (map #(or (:text %) ""))
       (str/join "\n")))

(defn inject-user-message!
  "Write a message into a conversation on the agent's behalf — a background job
  finishing, for instance — and give it a turn.

  When a turn is already running the loop has its own copy of the history, so
  the message would not be seen until the next turn; the turn's own end then
  starts the follow-up. Returns the row."
  [conv-id message]
  (let [st (conv-state conv-id)
        row (db/append-message! {:conversation-id conv-id
                                 :role (or (:role message) :user)
                                 :content (:content message)
                                 :user-data (:user-data message)
                                 :bump-timestamp true})
        _ (when-let [s (:sequence-id row)] (reset! (:seq st) s))]
    (emit-event! conv-id {:type :message-user
                          :text (message-text message)
                          :injected true
                          :sequence-id (:sequence-id row)
                          :user_data (:user-data message)})
    (notify-list-changed!)
    (if @(:running? st)
      ;; The loop may still have a round left; give it the message so the turn
      ;; answers it. If it has already finished its last round, the turn's exit
      ;; sees :resume-needed? and starts a follow-up.
      (do (swap! (:inject-buffer st) conj message)
          (reset! (:resume-needed? st) true))
      (start-turn! (or (db/get-conversation conv-id) {:id conv-id}) nil {}))
    row))

(defn start-turn!
  "Run a turn in the background, or queue the message if one is already in
  flight. Returns `:started` or `:queued`.

  The check and the claim share a lock: two requests arriving together must not
  both start a loop on one conversation."
  [conversation text opts]
  (let [conv-id (:id conversation)
        st (conv-state conv-id)]
    (locking (:turn-lock st)
      (if @(:running? st)
        (do (when text (enqueue! conv-id text)) :queued)
        (do (reset! (:running? st) true)
            (run-in-background
             conv-id
             (fn []
               (try
                 (drain-and-run (db/get-conversation conv-id)
                                (assoc opts :conversation conversation :text text))
                 (finally
                   (reset! (:running? st) false)
                   (notify-list-changed!)
                   (emit-event! conv-id {:type :state :agent_working false})
                   ;; Something arrived while we were running that the loop
                   ;; could not see. Turn it into a turn now that we are idle.
                   (when (compare-and-set! (:resume-needed? st) true false)
                     (start-turn! (or (db/get-conversation conv-id)
                                      {:id conv-id})
                                  nil {}))))))
            :started)))))
