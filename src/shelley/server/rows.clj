(ns shelley.server.rows
  "The two shapes the API promises: a conversation row with its derived state,
  and a message row as clients read it.

  Both are written out field by field rather than by dumping the database row.
  That is deliberate: the database has more columns than the contract, several
  are JSON text that must reach the wire as strings, and adding a column must
  never silently change the API."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [shelley.db :as db]
            [shelley.gitstate :as gitstate]
            [shelley.server.http :as http]
            [shelley.llm :as llm]))

;; ---------------------------------------------------------------------------
;; Git state

(defn- git-state
  "Repository state for a working directory, from `shelley.gitstate`, which runs
  the commands, caches for a couple of seconds and never throws. Without the
  cache every list request would spawn one git process per row."
  [cwd]
  (when (and cwd (not (str/blank? (str cwd))))
    (try (gitstate/state cwd) (catch Throwable _ nil))))

(defn forget-git-state! [] (gitstate/forget!))

;; ---------------------------------------------------------------------------
;; Conversations

(defn conversation-row
  "One conversation as the list and patch streams send it.

  `conv` is a decoded conversation row, usually from `db/list-conversations`
  (which already carries preview, subagent count and participants).
  opts: :git? (true) — include repository state."
  ([conv] (conversation-row conv {}))
  ([conv {:keys [git?] :or {git? true}}]
   (let [cwd (:cwd conv)
         repo (when git? (git-state cwd))
         dirty (when repo (:git_dirty repo))]
     (http/compact-map
      {;; Both names: the contract says conversation_id, and half the clients
       ;; (including our own e2e script) reach for id first.
       "id" (:id conv)
       "conversation_id" (:id conv)
       "slug" (:slug conv)
       "title" (:title conv)
       "created_at" (http/iso-time (:created_at conv))
       "updated_at" (http/iso-time (:updated_at conv))
       "cwd" cwd
       "archived" (boolean (:archived conv))
       "parent_conversation_id" (:parent_conversation_id conv)
       "model" (:model conv)
       "conversation_options" (:conversation_options conv)
       "current_generation" (:current_generation conv)
       "agent_working" (boolean (:agent-working conv))
       "working" (boolean (:agent-working conv))
       "user_initiated" (boolean (:user-initiated conv))
       "is_draft" (boolean (:is-draft conv))
       "turn_interrupted" (boolean (:turn-interrupted conv))
       "queued_messages" (let [q (:queued-messages conv)]
                           (cond (string? q) (or (http/parse-json-object q) [])
                                 (sequential? q) (vec q)
                                 :else []))
       "tags" (vec (or (:tags conv) []))
       "subagent_count" (:subagent-count conv)
       "preview" (or (:preview conv) "")
       "draft" (or (:draft conv) "")
       "preview_updated_at" (:preview-updated-at conv)
       "participants" (:participants conv)
       "max_sequence_id" (:max-sequence-id conv)
       "running_background_jobs" (:running-background-jobs conv)
       "git_repo_root" (:git_repo_root repo)
       ;; The toplevel git reports is the worktree root, which differs from the
       ;; repository root only when the directory is a linked worktree.
       "git_worktree_root" (:git_repo_root repo)
       "git_commit" (:git_commit repo)
       "git_subject" (:git_subject repo)
       "git_branch" (:git_branch repo)
       "git_dirty" (boolean (and dirty (pos? (long dirty))))
       "git_dirty_count" dirty
       "search_snippet" (:snippet conv)
       "search_snippet_message_id" (:snippet-message-id conv)}))))

(defn snapshot-rows
  "The full unarchived list, parents and subagents, as the snapshot and the
  patch stream see it. One query, then git state from the cache."
  []
  (mapv conversation-row
        (db/list-conversations {:limit 5000 :offset 0 :include-subagents true})))

;; ---------------------------------------------------------------------------
;; Messages

(defn- block->api
  "Strip what a client must not receive: inline base64 image bytes become a
  URL that serves them from the message row, and provider continuation secrets
  stay on the server.

  `message-id` and a running block index make the URL stable enough to cache,
  which is why the index is threaded through rather than recomputed."
  [message-id block index]
  (let [b (cond-> block
            true (update :type (fn [t] (if (keyword? t) t (keyword (str t))))))]
    (case (:type b)
      :image (let [mt (or (:media-type b) (:media_type b) "image/png")]
               {:type "image"
                :mediaType mt
                :media_type mt
                :displayImageUrl (str "/api/message/" message-id "/image/" index "/0")})
      :thinking (dissoc b :signature :data :openai-responses-reasoning)
      :tool-use (dissoc b :openai-responses-reasoning)
      :tool-result (update b :content #(mapv (fn [i c] (block->api message-id c i))
                                             (range) (or % [])))
      b)))

(defn- content->api [message-id content]
  (mapv (fn [i b] (block->api message-id b i)) (range) (or content [])))

(defn llm-data-json
  "The message body as `llm_data`: a JSON *string*, as clients expect."
  [message]
  (http/json-string {"role" (name (or (:role message) :user))
                     "content" (content->api (:message-id message) (:content message))}))

(defn end-of-turn?
  "An agent row ends the turn when it has no tool calls left to run."
  [message]
  (and (= :assistant (:role message))
       (empty? (llm/tool-uses message))))

(defn api-message
  [message]
  (let [row (http/compact-map
             {"message_id" (:message-id message)
              "conversation_id" (:conversation-id message)
              "sequence_id" (:sequence-id message)
              "type" (name (or (:type message) :user))
              "llm_data" (llm-data-json message)
              "user_data" (http/json-string (:user-data message))
              ;; Usage reaches the wire in the original's spelling
              ;; (`input_tokens`), because a client written against it reads
              ;; these keys by name.
              "usage_data" (http/json-string (http/snake-keys (:usage-data message)))
              "other_usage_data" (http/json-string (http/snake-keys (:other-usage message)))
              "created_at" (http/iso-time (:created-at message))
              "display_data" (http/json-string (:display-data message))
              "generation" (:generation message)
              "llm_api_url" (:llm-api-url message)
              "model_name" (:model-name message)
              "forked_from_message_id" (:forked-from message)
              "user_email" (:user-email message)
              "excluded_from_context" (boolean (:excluded-from-context message))})]
    (cond-> row
      (= :assistant (:role message)) (assoc "end_of_turn" (end-of-turn? message))
      (:error message) (assoc "error" (name (:type (:error message)))
                             "retryable" (boolean (:retryable (:error message)))))))

(defn api-messages [messages] (mapv api-message messages))
