(ns shelley.server.btw-api
  "Side-question endpoints.

  A btw is a conversation with `kind: \"btw\"` whose system prompt carries a
  frozen snapshot of its parent. These routes create one, list them, summarise
  one into the main thread, and dismiss one.

  They are separate from the conversation routes because a btw is not part of
  the conversation's own history: it hangs off it."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [shelley.btw :as btw]
            [shelley.db :as db]
            [shelley.server.http :as http]
            [shelley.llm :as llm]
            [shelley.server.runtime :as runtime]))

(defn- parent-or-404 [id]
  (or (db/get-conversation id)
      (http/error-response 404 "conversation not found")))

(defn create-btw
  "POST /api/conversation/:id/btw  {message, message_id?}

  Freezes the parent transcript now, creates the btw conversation, and starts
  its turn."
  [{:keys [params] :as req}]
  (if-let [parent (parent-or-404 (:id params))]
    (let [body (http/parse-body req)
          message (or (:message body) (:text body))]
      (if (clojure.string/blank? (str message))
        (http/error-response 400 "message is required")
        (let [parent-id (:id parent)
              reference (btw/frozen-reference (db/messages parent-id {}))
              conv (btw/create! {:parent-id parent-id
                                 :parent-message-id (:message_id body)
                                 :model (or (:model body) (:model parent) runtime/default-model)
                                 :cwd (:cwd parent)
                                 :title (or (:title body) "btw")})]
          ;; No tools: a btw answers from the frozen transcript. The prompt is
          ;; appended as extra system context so the runtime keeps owning the
          ;; base prompt.
          (runtime/start-turn! conv (str message)
                               {:tools []
                                :system-extra (btw/system-prompt reference)})
          (http/json-response 201 {:conversation conv}))))
    (parent-or-404 (:id params))))

(defn list-btws
  "GET /api/conversation/:id/btw — the btw threads hanging off a conversation."
  [{:keys [params]}]
  (if-let [_ (db/get-conversation (:id params))]
    (http/json-response 200
                        {:btws (->> (db/subagents (:id params))
                                    (filter btw/btw?)
                                    (mapv (fn [c] (assoc c :dismissed
                                                         (boolean (get-in c [:options :dismissed]))))))})
    (http/error-response 404 "conversation not found")))

(defn- btw-of [parent-id btw-id]
  (let [c (db/get-conversation btw-id)]
    (when (and c (btw/btw?) (= parent-id (btw/parent-id c)))
      c)))

(defn dismiss-btw
  "POST /api/conversation/:id/btw/:btw_id/dismiss"
  [{:keys [params]}]
  (if-let [c (btw-of (:id params) (:btw_id params))]
    (do (db/update-conversation-options! (:id c) (btw/dismiss-options c))
        (http/json-response 200 {:ok true}))
    (http/error-response 404 "btw not found")))

(defn summarize-btw
  "POST /api/conversation/:id/btw/:btw_id/summarize

  Returns a two-sentence gist of the btw thread for showing inline in the parent.
  Summarising is a separate call from the btw itself so a reader can ask for the
  short version later, or after several follow-ups."
  [{:keys [params] :as req}]
  (if-let [c (btw-of (:id params) (:btw_id params))]
    (let [msgs (db/messages (:id c) {})
          turns (->> msgs
                     (filter #(contains? #{:user :agent} (:type %)))
                     (map (fn [m] (str (if (= :user (:type m)) "Q: " "A: ")
                                       (apply str (keep :text (:content m))))))
                     (clojure.string/join "\n\n"))
          question (or (:message (http/parse-body req)) "the side question")
          prompt (btw/summarize-prompt question turns)]
      (if (clojure.string/blank? turns)
        (http/json-response 200 {:summary nil :reason "nothing to summarize yet"})
        (try
          ;; One non-streaming call: no tools, no history, just a gist.
          (let [resp (llm/complete {:model (:model c)
                                    :system ["You summarize side questions in at most two sentences."]
                                    :messages [(llm/user-message prompt)]
                                    :tools []})]
            (http/json-response 200 {:summary (llm/text-of resp)}))
          (catch Exception e
            (log/warn e "btw summarize failed")
            (http/error-response 502 (str "could not summarize: " (.getMessage e)))))))
    (http/error-response 404 "btw not found")))

(def routes
  [["/api/conversation/:id/btw" {:get list-btws :post create-btw}]
   ["/api/conversation/:id/btw/:btw_id/dismiss" {:post dismiss-btw}]
   ["/api/conversation/:id/btw/:btw_id/summarize" {:post summarize-btw}]])
