(ns shelley.db
  "Persistence.

  Messages are append-only: a turn that crashes halfway leaves the transcript
  intact, and resuming is just reading the rows back. Content blocks are stored
  as JSON because the block vocabulary is the model's, not SQL's.

  The schema is the Go original's (db/schema/*.sql), applied by
  `shelley.db.migrations`. Everything the server needs is a function here:
  callers pass and receive plain maps with keyword keys and never write SQL.

  Naming conventions, so the two shapes don't get confused:

    * rows are decoded to kebab-case keywords (`:message-id`, `:updated-at`,
      `:queued-messages`); the snake_case JSON field names of the original
      (`input_tokens`, `thinking_level`) become `:input-tokens`,
      `:thinking-level` on the way out and back again on the way in, so the
      JSON on disk stays wire-compatible with the original.
    * content blocks are *not* re-keyed: `llm/normalize-block` restores their
      `:type` keywords after a round trip, and their other keys are already
      kebab-case both ways.
    * a couple of legacy keys survive on conversations because `shelley.server`
      reads them: `:id`, `:working_dir`, `:updated_at`."
  (:require [shelley.db.migrations :as migrations]
            [shelley.llm :as llm]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ---------------------------------------------------------------------------
;; Connection

(def ^:dynamic *db-path*
  "Overrides the database file. Tests bind this; production reads SHELLEY_DB."
  nil)

(defonce ^:private datasources (atom {}))

(defn db-path []
  (or *db-path*
      (System/getenv "SHELLEY_DB")
      (str (System/getProperty "user.home") "/.clj-shelley/shelley.db")))

(defn- connectable [path]
  (let [f (io/file path)]
    (some-> (.getParentFile f) (.mkdirs))
    ;; The pragmas ride in the URL rather than being issued once: a datasource
    ;; hands out a fresh connection per operation, so a PRAGMA executed at
    ;; startup would apply to exactly one of them. WAL persists in the file;
    ;; busy_timeout and foreign_keys do not.
    (jdbc/get-datasource
     {:dbtype "sqlite"
      :dbname (str (.getPath f) "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on")})))

(defn ds []
  (let [path (db-path)]
    (or (get @datasources path)
        (locking datasources
          (or (get @datasources path)
              (let [conn (connectable path)]
                (swap! datasources assoc path conn)
                conn))))))

(defn init!
  "Create or upgrade the schema. Safe to call on every start."
  []
  (let [n (migrations/migrate! (ds))]
    (log/debug "database ready" (db-path) n "migrations")
    nil))

;; ---------------------------------------------------------------------------
;; Value codecs

(def ^:private rs-opts {:builder-fn rs/as-unqualified-lower-maps})

(defn- snake-key [k]
  (cond (keyword? k) (str/replace (name k) "-" "_")
        (string? k) (str/replace k "-" "_")
        :else (str k)))

(defn- kebab-key [k]
  (if (and (keyword? k) (str/includes? (name k) "_"))
    (keyword (str/replace (name k) "_" "-"))
    k))

(defn- snake-keys [v]
  (cond (map? v) (reduce-kv (fn [m k val] (assoc m (snake-key k) (snake-keys val))) {} v)
        (sequential? v) (mapv snake-keys v)
        :else v))

(defn- plainify
  "Rebuild parsed JSON as plain data with kebab-case keys.

  Cheshire hands back lazy seqs for JSON arrays; realizing them here keeps a
  row's value from holding on to a parser, and vectors are what every caller
  (and `pr-str` in a log line) expects."
  [v]
  (cond (map? v) (reduce-kv (fn [m k x] (assoc m (kebab-key k) (plainify x))) {} v)
        (sequential? v) (mapv plainify v)
        :else v))

(defn- force-vectors
  "Like plainify but keeps keys byte-identical: content blocks round-trip
  through JSON unchanged."
  [v]
  (cond (map? v) (reduce-kv (fn [m k x] (assoc m k (force-vectors x))) {} v)
        (sequential? v) (mapv force-vectors v)
        :else v))

(defn- ->json
  "Encode a map/vector for a JSON column. `:input-tokens` becomes
  `input_tokens`, matching the original's wire spelling."
  [v]
  (when (some? v) (json/generate-string (snake-keys v))))

(defn- <-json [s]
  (when (and s (not= "" s))
    (try (plainify (json/parse-string s true))
         (catch Exception e
           (log/warn "ignoring unparseable JSON column" (.getMessage e))
           nil))))

(defn- codec->json
  "Content blocks keep their own keys: `json/generate-string` writes
  `:tool-use-id` as `tool-use-id` and the keywordised read restores it, so no
  key transformation is applied (or wanted) here."
  [blocks]
  (when (some? blocks) (json/generate-string blocks)))

(defn- codec<-json [s]
  (when (and s (not= "" s))
    (try (force-vectors (json/parse-string s true))
         (catch Exception e
           (log/warn "ignoring unparseable content column" (.getMessage e))
           nil))))

(defn- ->bool [v]
  (cond (nil? v) false
        (boolean? v) v
        (number? v) (not (zero? v))
        (string? v) (contains? #{"1" "true" "TRUE" "t"} v)
        :else (boolean v)))

(defn- ->int [v] (when (some? v) (long v)))

(def ^:private sql-datetime #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?$")

(defn- iso-time
  "SQLite dates are `YYYY-MM-DD HH:MM:SS` in UTC; callers (and JSON) want
  RFC 3339, which sorts identically and parses in JavaScript."
  [v]
  (if (and (string? v) (re-find sql-datetime v))
    (str (str/replace v " " "T") "Z")
    v))

(defn now
  "Epoch milliseconds. Kept for callers that want a number; SQL timestamps are
  written by SQLite itself."
  []
  (System/currentTimeMillis))

(defn- sql-timestamp
  "The same text SQLite's CURRENT_TIMESTAMP produces."
  []
  (str (.format (java.time.LocalDateTime/now java.time.ZoneOffset/UTC)
                (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))))

;; ---------------------------------------------------------------------------
;; Small query helpers

(defn- query [sql & args]
  (jdbc/execute! (ds) (into [sql] args) rs-opts))

(defn- query1 [sql & args]
  (jdbc/execute-one! (ds) (into [sql] args) rs-opts))

(defn- execute! [sql & args]
  (jdbc/execute! (ds) (into [sql] args) rs-opts))

(defn- tx-query [tx sql & args]
  (jdbc/execute! tx (into [sql] args) rs-opts))

(defn- tx-query1 [tx sql & args]
  (jdbc/execute-one! tx (into [sql] args) rs-opts))

(defn- tx-execute! [tx sql & args]
  (jdbc/execute! tx (into [sql] args) rs-opts))

(defn- new-id [] (str (java.util.UUID/randomUUID)))

(defn- affected
  "Rows changed by a DML statement. next.jdbc reports a bare count for DELETE and
  a `:next.jdbc/update-count` map for UPDATE ... RETURNING, so callers cannot
  read either shape portably."
  [result]
  (let [row (first result)]
    (if (map? row) (or (:next.jdbc/update-count row) 0) (or row 0))))

;; ---------------------------------------------------------------------------
;; Decoding rows

(def ^:private type->role
  {"user" :user
   "agent" :assistant      ; the model's own turn
   "system" :system
   "tool" :user            ; tool results ride in user turns
   "error" :error
   "warning" :warning
   "gitinfo" :gitinfo
   "modelchange" :modelchange
   "slug" :slug
   "livetranscript" :livetranscript
   "inplacecompaction" :inplacecompaction})

(defn- role->type [role]
  (case (keyword role)
    :assistant "agent"
    :agent "agent"
    :user "user"
    :system "system"
    :tool "tool"
    :error "error"
    (name role)))

(defn decode-message
  "Decode one `messages` row.

  `:content` is the block vector, normalised through `llm/normalize-block`:
  JSON has no keywords, so without that step every block's `:type` comes back
  as a string and providers silently see nothing they recognise."
  [row]
  (when row
    (let [type (some-> (:type row) name keyword)]
      {:message-id (:message_id row)
       :conversation-id (:conversation_id row)
       :sequence-id (:sequence_id row)
       :type type
       :role (get type->role (name (or type :system)) (keyword (name (or type :system))))
       :content (mapv llm/normalize-block (or (codec<-json (:llm_data row)) []))
       :user-data (<-json (:user_data row))
       :usage-data (<-json (:usage_data row))
       :display-data (<-json (:display_data row))
       :other-usage (<-json (:other_usage_data row))
       :excluded-from-context (->bool (:excluded_from_context row))
       :generation (:generation row)
       :llm-api-url (:llm_api_url row)
       :model-name (:model_name row)
       :user-email (:user_email row)
       :forked-from (:forked_from_message_id row)
       :external-message-id (:external_message_id row)
       :created-at (iso-time (:created_at row))})))

(defn decode-conversation
  "Decode one `conversations` row.

  `:id`, `:working_dir` and `:updated_at` are kept beside the cleaner
  `:conversation-id`, `:cwd` and `:updated-at` because `shelley.server` reads
  them; everything new should use the kebab-case names."
  [row]
  (when row
    (let [id (:conversation_id row)
          options (or (<-json (:conversation_options row)) {})]
      (assoc row
             :id id
             :conversation-id id
             :created_at (iso-time (:created_at row))
             :updated_at (iso-time (:updated_at row))
             :working_dir (:cwd row)
             :user-initiated (->bool (:user_initiated row))
             :archived (->bool (:archived row))
             :agent-working (->bool (:agent_working row))
             :is-draft (->bool (:is_draft row))
             :turn-interrupted (->bool (:turn_interrupted row))
             :current-generation (->int (:current_generation row))
             :parent-id (:parent_conversation_id row)
             :external-conversation-id (:external_conversation_id row)
             :external-endpoint (:external_endpoint row)
             :tags (or (<-json (:tags row)) [])
             :options options
             :thinking-level (:thinking_level options)
             :queued-messages (or (<-json (:queued_messages row)) [])
             :created-at (iso-time (:created_at row))
             :updated-at (iso-time (:updated_at row))))))

(defn- decode-list-item [row]
  (when row
    (let [packed (:preview_packed row)
          tail (or (when (and (string? packed) (> (count packed) 20)) (subs packed 20)) "")
          stamp (when (and (string? packed) (> (count packed) 20)) (subs packed 0 20))]
      (assoc (decode-conversation row)
             :preview tail
             :preview-updated-at stamp
             :max-sequence-id (:max_sequence_id row)
             :participants (or (<-json (:participants_json row)) [])
             :subagent-count (:subagent_count row)
             :running-background-jobs (:running_background_jobs row)
             :slug-rank (:slug_rank row)))))

;; ---------------------------------------------------------------------------
;; Conversations: reading

(def ^:private preview-packed-sql
  (str "CAST(COALESCE((
          SELECT strftime('%Y-%m-%dT%H:%M:%SZ', m.created_at) || substr((
                   SELECT je.value ->> 'text'
                     FROM json_each(CASE WHEN json_valid(m.llm_data) THEN m.llm_data ELSE '[]' END) je
                    WHERE je.value ->> 'type' = 'text' AND je.value ->> 'text' <> ''
                    ORDER BY je.key DESC LIMIT 1), 1, 300)
            FROM messages m
           WHERE m.conversation_id = c.conversation_id AND m.type = 'agent'
             AND EXISTS (SELECT 1 FROM json_each(CASE WHEN json_valid(m.llm_data) THEN m.llm_data ELSE '[]' END) je
                          WHERE je.value ->> 'type' = 'text' AND je.value ->> 'text' <> '')
           ORDER BY m.sequence_id DESC LIMIT 1), '') AS TEXT) AS preview_packed"))

(def ^:private derived-columns-sql
  (str preview-packed-sql
       ", CAST(COALESCE((SELECT MAX(m.sequence_id) FROM messages m
                           WHERE m.conversation_id = c.conversation_id), 0) AS INTEGER) AS max_sequence_id
        , CAST(COALESCE((SELECT json_group_array(json_object('email', p.user_email, 'message_count', p.n))
                           FROM (SELECT m.user_email, COUNT(*) AS n FROM messages m
                                  WHERE m.conversation_id = c.conversation_id
                                    AND m.user_email IS NOT NULL AND m.user_email <> ''
                                  GROUP BY m.user_email) p), '[]') AS TEXT) AS participants_json
        , CAST((SELECT COUNT(*) FROM conversations sub
                 WHERE sub.parent_conversation_id = c.conversation_id) AS INTEGER) AS subagent_count
        , CAST((SELECT COUNT(*) FROM background_jobs bj
                 WHERE bj.conversation_id = c.conversation_id AND NOT bj.exited) AS INTEGER) AS running_background_jobs"))

(def ^:private list-item-select
  (str "SELECT c.*, " derived-columns-sql " FROM conversations c"))

(defn get-conversation [id]
  (decode-conversation (query1 "SELECT * FROM conversations WHERE conversation_id = ?" id)))

(defn get-conversation-by-slug [slug]
  (decode-conversation (query1 "SELECT * FROM conversations WHERE slug = ?" slug)))

(defn get-conversation-by-slug-and-parent [slug parent-id]
  (decode-conversation
   (query1 "SELECT * FROM conversations WHERE slug = ? AND parent_conversation_id IS ?" slug parent-id)))

(defn get-conversation-by-external-id [external-id]
  (decode-conversation
   (query1 "SELECT * FROM conversations WHERE external_conversation_id = ?" external-id)))

(defn list-conversations
  "The conversation list, newest activity first.

  No arguments reproduces the original's ListConversations: unarchived,
  top-level conversations with the derived fields the list needs (preview,
  max sequence id, participants, subagent and job counts).

  opts: :limit (100) :offset (0) :archived (false) :include-subagents (false)
        :q — a slug substring match, the non-FTS search the original uses when
             the client has not asked for content search."
  ([] (list-conversations {}))
  ([{:keys [limit offset archived include-subagents] q-arg :q :or {limit 100 offset 0}}]
   (let [where (cond-> ["c.archived = ?"]
                 (not include-subagents) (conj "c.parent_conversation_id IS NULL")
                 (some? q-arg) (conj "c.slug LIKE ?"))
         args (cond-> [(if archived 1 0)]
                (some? q-arg) (conj (str "%" q-arg "%")))]
     (->> (apply query (str list-item-select " WHERE " (str/join " AND " where)
                        " ORDER BY c.updated_at DESC LIMIT ? OFFSET ?")
                 (concat args [limit offset]))
          (mapv decode-list-item)))))

(defn count-conversations []
  (:n (query1 "SELECT COUNT(*) AS n FROM conversations
            WHERE archived = FALSE AND parent_conversation_id IS NULL")))

(defn subagents [parent-id]
  (->> (query "SELECT * FROM conversations WHERE parent_conversation_id = ? ORDER BY created_at ASC" parent-id)
       (mapv decode-conversation)))

(defn participants
  "Authors of a conversation's messages, from the exe.dev account stamped on
  user messages, keyed by conversation id."
  [conversation-ids]
  (if (empty? conversation-ids)
    {}
    (->> (query "SELECT conversation_id, user_email, COUNT(*) AS n FROM messages
               WHERE conversation_id IN (SELECT value FROM json_each(?))
                 AND user_email IS NOT NULL AND user_email <> ''
               GROUP BY conversation_id, user_email"
            (json/generate-string (vec conversation-ids)))
         (reduce (fn [acc row]
                   (update acc (:conversation_id row) (fnil conj [])
                           {:email (:user_email row) :message-count (:n row)}))
                 {}))))

;; ---------------------------------------------------------------------------
;; Conversations: writing

(defn- normalize-slug [slug]
  (let [s (some-> slug str str/trim)]
    (when-not (str/blank? s) s)))

(defn- insert-conversation! [tx {:keys [id slug title model cwd user-initiated parent-id options
                                        is-draft draft external-conversation-id external-endpoint
                                        created-at]}]
  (let [id (or id (new-id))]
    (try
      (tx-execute! tx "INSERT INTO conversations
                      (conversation_id, slug, title, model, cwd, user_initiated,
                       parent_conversation_id, conversation_options, is_draft, draft,
                       external_conversation_id, external_endpoint, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,COALESCE(?, CURRENT_TIMESTAMP), COALESCE(?, CURRENT_TIMESTAMP))"
                id (normalize-slug slug) title model cwd (if (false? user-initiated) 0 1)
                parent-id (or (->json options) "{}") (if is-draft 1 0) (or draft "")
                external-conversation-id external-endpoint created-at created-at)
      (catch Exception e
        (if (re-find #"UNIQUE constraint failed: conversations\.(slug|external_conversation_id)" (str (.getMessage e)))
          (throw (ex-info (str "conversation identity already taken: " (.getMessage e))
                          {:slug (normalize-slug slug)
                           :external-conversation-id external-conversation-id
                           :slug-taken true}
                          e))
          (throw e))))
    (decode-conversation (tx-query1 tx "SELECT * FROM conversations WHERE conversation_id = ?" id))))

(defn create-conversation!
  "Create a conversation.

  Keys: :slug :title :model :working-dir (or :cwd) :user-initiated (default
  true; pass false for subagents) :parent-id :options :is-draft :draft :id.
  Returns the stored row."
  [{:keys [working-dir cwd] :as params}]
  (jdbc/with-transaction [tx (ds)]
    (insert-conversation! tx (assoc params :cwd (or cwd working-dir (System/getProperty "user.dir"))))))

(defn create-subagent-conversation!
  "A conversation created by a tool rather than by a person: `user_initiated`
  is false and it hangs off its parent, so the default list leaves it out."
  [{:keys [parent-id slug cwd model]}]
  (create-conversation! {:slug slug
                         :title (when slug (str "subagent: " slug))
                         :model model
                         :cwd (or cwd (:cwd (get-conversation parent-id)))
                         :user-initiated false
                         :parent-id parent-id}))

(defn create-channel-conversation!
  "The conversation bound to an external chat (a channel)."
  [{:keys [external-conversation-id external-endpoint model options]}]
  (create-conversation! {:external-conversation-id external-conversation-id
                         :external-endpoint external-endpoint
                         :model model
                         :options options}))

(defn create-draft-conversation!
  "A conversation with no messages whose body lives in `draft` until the first
  message promotes it. Drafts appear in the list like any other conversation."
  [{:keys [draft cwd model options]}]
  (create-conversation! {:draft (or draft "") :is-draft true :cwd cwd :model model
                         :options options}))

(defn touch!
  "Bump updated_at, and set the title when one is given."
  [id title]
  (execute! "UPDATE conversations SET updated_at = CURRENT_TIMESTAMP, title = COALESCE(?, title)
          WHERE conversation_id = ?" title id)
  nil)

(defn update-cwd! [id cwd]
  (execute! "UPDATE conversations SET cwd = ?, updated_at = CURRENT_TIMESTAMP WHERE conversation_id = ?" cwd id))

(defn set-model!
  "Set the model only while the conversation has none (backfilling old rows)."
  [id model]
  (execute! "UPDATE conversations SET model = ? WHERE conversation_id = ? AND model IS NULL" model id))

(defn force-set-model! [id model]
  (execute! "UPDATE conversations SET model = ?, updated_at = CURRENT_TIMESTAMP
          WHERE conversation_id = ?" model id))

(defn set-slug! [id slug]
  (decode-conversation
   (query1 "UPDATE conversations SET slug = ?, updated_at = CURRENT_TIMESTAMP
        WHERE conversation_id = ? RETURNING *" (normalize-slug slug) id)))

(defn slug-candidate
  "The slug to try on a given attempt when `base` may already be taken: base,
  base-1, base-2, base-3, then base-<random>."
  [base attempt]
  (cond
    (zero? attempt) base
    (<= attempt 3) (str base "-" attempt)
    :else (str base "-" (str/lower-case (subs (str (java.util.UUID/randomUUID)) 0 6)))))

(defn set-slug-if-unset!
  "Install the slug only while the conversation is still unnamed. Read and write
  share one transaction so an async slug guess cannot overwrite a rename.
  Returns [conversation updated?]."
  [id slug]
  (jdbc/with-transaction [tx (ds)]
    (let [conv (tx-query1 tx "SELECT * FROM conversations WHERE conversation_id = ?" id)]
      (cond
        (nil? conv) [nil false]
        (not (str/blank? (:slug conv))) [(decode-conversation conv) false]
        :else [(decode-conversation (tx-query1 tx "UPDATE conversations SET slug = ?, updated_at = CURRENT_TIMESTAMP
                                                WHERE conversation_id = ? RETURNING *"
                                             (normalize-slug slug) id))
               true]))))

(defn delete-conversation!
  "Delete a conversation and everything that hangs off it.

  Children are deleted explicitly rather than relying on ON DELETE CASCADE, so
  the delete works on a connection where foreign keys are not enforced."
  [id]
  (jdbc/with-transaction [tx (ds)]
    (tx-execute! tx "DELETE FROM messages WHERE conversation_id = ?" id)
    (tx-execute! tx "DELETE FROM background_jobs WHERE conversation_id = ?" id)
    (tx-execute! tx "DELETE FROM live_call_transcripts WHERE conversation_id = ?" id)
    (tx-execute! tx "DELETE FROM live_calls WHERE conversation_id = ?" id)
    (tx-execute! tx "DELETE FROM iframes WHERE conversation_id = ?" id)
    (tx-execute! tx "DELETE FROM conversations WHERE conversation_id = ?" id))
  nil)

;; ---------------------------------------------------------------------------
;; Archive, tags, drafts

(defn set-archived! [id archived?]
  (decode-conversation
   (query1 "UPDATE conversations SET archived = ? WHERE conversation_id = ? RETURNING *"
       (if archived? 1 0) id)))

(defn archive-conversation! [id] (set-archived! id true))
(defn unarchive-conversation! [id] (set-archived! id false))

(defn tags [id]
  (or (<-json (:tags (query1 "SELECT tags FROM conversations WHERE conversation_id = ?" id))) []))

(defn set-tags!
  "Replace the tag list. Tagging is metadata only: it deliberately does not
  bump updated_at, so retagging an old conversation does not reorder the list."
  [id tag-list]
  (let [clean (->> tag-list (map str) (remove str/blank?) distinct vec)]
    (decode-conversation
     (query1 "UPDATE conversations SET tags = ? WHERE conversation_id = ? RETURNING *"
         (json/generate-string clean) id))))

(defn add-tag! [id tag]
  (let [current (tags id)]
    (set-tags! id (conj current tag))))

(defn remove-tag! [id tag]
  (let [current (tags id)]
    (set-tags! id (remove #(= % (str tag)) current))))

(defn list-tagged
  "Conversations carrying `tag`, newest activity first."
  ([tag] (list-tagged tag {}))
  ([tag {:keys [limit offset archived] :or {limit 100 offset 0}}]
   (->> (query (str list-item-select
                " WHERE EXISTS (SELECT 1 FROM json_each(c.tags) t WHERE t.value = ?)"
                " AND c.archived = ? ORDER BY c.updated_at DESC LIMIT ? OFFSET ?")
           (str tag) (if archived 1 0) limit offset)
        (mapv decode-list-item))))

(defn update-draft!
  "Update a draft in place. Nil keys keep their value, so text, model and cwd
  each update independently. Returns nil when the conversation is no longer a
  draft (deleted, or promoted by a concurrent send)."
  [id {:keys [draft model cwd]}]
  (decode-conversation
   (query1 "UPDATE conversations
           SET draft = COALESCE(?, draft), model = COALESCE(?, model), cwd = COALESCE(?, cwd),
               updated_at = CURRENT_TIMESTAMP
         WHERE conversation_id = ? AND is_draft = TRUE
        RETURNING *"
       draft model cwd id)))

(defn promote-draft!
  "Clear draft state when the user sends the first message. Returns nil when the
  conversation is no longer a draft."
  [id]
  (jdbc/with-transaction [tx (ds)]
    (let [row (tx-query1 tx "UPDATE conversations SET is_draft = FALSE, draft = '', updated_at = CURRENT_TIMESTAMP
                        WHERE conversation_id = ? AND is_draft = TRUE RETURNING *" id)]
      (when row
        ;; A draft's options describe a body that no longer exists as a body:
        ;; the search/btw kinds and parent pointers only apply pre-promote.
        (tx-execute! tx "UPDATE conversations
                         SET conversation_options = json_remove(conversation_options, '$.kind', '$.parent_pointer')
                       WHERE conversation_id = ?" id)
        (decode-conversation (tx-query1 tx "SELECT * FROM conversations WHERE conversation_id = ?" id))))))

;; ---------------------------------------------------------------------------
;; Generations, options, runtime state

(defn increment-generation!
  "Start a new generation (after a compaction). Also resolves a prior
  interruption: the transcript has been summarised, so there is nothing to
  continue."
  [id]
  (decode-conversation
   (query1 "UPDATE conversations
           SET current_generation = current_generation + 1, turn_interrupted = FALSE,
               updated_at = CURRENT_TIMESTAMP
         WHERE conversation_id = ? RETURNING *" id)))

(defn set-generation!
  "Roll a failed compaction back to the previous generation."
  [id generation]
  (decode-conversation
   (query1 "UPDATE conversations SET current_generation = ?, turn_interrupted = FALSE,
               updated_at = CURRENT_TIMESTAMP
         WHERE conversation_id = ? RETURNING *" generation id)))

(defn conversation-options [id]
  (or (<-json (:conversation_options (query1 "SELECT conversation_options FROM conversations
                                           WHERE conversation_id = ?" id))) {}))

(defn update-conversation-options!
  "Replace the whole options object."
  [id options]
  (execute! "UPDATE conversations SET conversation_options = ? WHERE conversation_id = ?"
         (->json options) id)
  (conversation-options id))

(defn modify-conversation-options!
  "Apply `f` to the options inside one transaction and write them back only if
  it reports a change. Returns [options changed?]."
  [id f]
  (jdbc/with-transaction [tx (ds)]
    (let [raw (:conversation_options (tx-query1 tx "SELECT conversation_options FROM conversations
                                                WHERE conversation_id = ?" id))
          opts (or (<-json raw) {})
          next-opts (f opts)]
      (if (= next-opts opts)
        [opts false]
        (do (tx-execute! tx "UPDATE conversations SET conversation_options = ? WHERE conversation_id = ?"
                      (->json next-opts) id)
            ;; Hand back what a later read will see, not the map the caller
            ;; built: the round trip through JSON is what canonicalises keys.
            [(or (<-json (:conversation_options
                          (tx-query1 tx "SELECT conversation_options FROM conversations
                                          WHERE conversation_id = ?" id)))
                 {})
             true])))))

(defn set-conversation-option!
  "Set one option key (`:thinking-level`, `:kind`, `:system-prompt`, ...)."
  [id k v]
  (first (modify-conversation-options! id #(assoc % k v))))

(defn set-thinking-level!
  "Set the reasoning level (`off`, `minimal`, `low`, `medium`, `high`, `xhigh`,
  or nil for the service default) without disturbing other options."
  [id level]
  (first (modify-conversation-options! id #(assoc % :thinking_level level))))

(defn mark-settings-edited!
  "Record that the conversation's settings were changed after being taken from
  its profile, so profile edits stop flowing into it."
  [id]
  (execute! "UPDATE conversations
             SET conversation_options = json_set(conversation_options, '$.settings_edited', json('true'))
           WHERE conversation_id = ?" id)
  nil)

(defn set-agent-working!
  "Persist whether a turn is running. Deliberately does not bump updated_at:
  runtime state changes must not reorder the conversation list. Starting a turn
  also clears a prior interruption."
  [id working?]
  (execute! "UPDATE conversations
             SET agent_working = ?,
                 turn_interrupted = CASE WHEN ? THEN FALSE ELSE turn_interrupted END
           WHERE conversation_id = ?"
         (if working? 1 0) (if working? 1 0) id)
  nil)

(defn reset-all-agent-working!
  "Called on startup: no stale TRUE survives a restart."
  []
  (execute! "UPDATE conversations SET agent_working = FALSE WHERE agent_working = TRUE") nil)

(defn list-agent-working-conversation-ids []
  (mapv :conversation_id (query "SELECT conversation_id FROM conversations WHERE agent_working = TRUE
                             ORDER BY updated_at DESC")))

(defn set-turn-interrupted! [id interrupted?]
  (execute! "UPDATE conversations SET turn_interrupted = ? WHERE conversation_id = ?"
         (if interrupted? 1 0) id)
  nil)

(defn claim-interrupted-turn!
  "Atomically move a manually resumed conversation from interrupted+idle to
  working. False means another request already claimed it."
  [id]
  (= 1 (affected (execute! "UPDATE conversations SET turn_interrupted = FALSE, agent_working = TRUE
                             WHERE conversation_id = ? AND turn_interrupted = TRUE
                               AND agent_working = FALSE AND parent_conversation_id IS NULL" id))))

(defn update-parent! [id parent-id]
  (decode-conversation
   (query1 "UPDATE conversations SET parent_conversation_id = ?, updated_at = CURRENT_TIMESTAMP
         WHERE conversation_id = ? RETURNING *" parent-id id)))

;; ---------------------------------------------------------------------------
;; Queued messages

(defn- normalize-queued
  "`queued_messages` holds the raw message to feed the loop on drain. JSON has
  no keywords, so `:role` and every block's `:type` come back as strings;
  restore them here, where the queue is read, rather than at each drain site."
  [msgs]
  (mapv (fn [entry]
          (cond-> entry
            (:llm entry)
            (update :llm (fn [message]
                           (cond-> message
                             (:role message)
                             (update :role #(if (string? %) (keyword %) %))
                             (:content message)
                             (update :content (fn [c] (mapv llm/normalize-block c))))))))
        msgs))

(defn parse-queued-messages
  "Parse a `queued_messages` column. A non-empty value that will not parse is an
  error, never silently `[]`: overwriting it would drop user input that has not
  been drained yet."
  [raw]
  (cond
    (or (nil? raw) (= "" raw)) []
    :else (try
            (let [v (plainify (json/parse-string raw true))]
              (if (sequential? v) (normalize-queued (vec v)) []))
            (catch Exception e
              (throw (ex-info "corrupt queued_messages column" {:value raw} e))))))

(defn queued-messages [conversation-id]
  (parse-queued-messages
   (:queued_messages (query1 "SELECT queued_messages FROM conversations WHERE conversation_id = ?"
                         conversation-id))))

(defn get-queued-message [conversation-id queued-id]
  (first (filter #(= queued-id (:id %)) (queued-messages conversation-id))))

(defn- write-queued! [tx conversation-id msgs]
  (tx-execute! tx "UPDATE conversations SET queued_messages = ?, updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ?"
            (->json (vec msgs)) conversation-id))

(defn- mutate-queued! [conversation-id f]
  (jdbc/with-transaction [tx (ds)]
    (let [raw (:queued_messages (tx-query1 tx "SELECT queued_messages FROM conversations
                                            WHERE conversation_id = ?" conversation-id))
          msgs (parse-queued-messages raw)
          next-msgs (f msgs)]
      (write-queued! tx conversation-id next-msgs)
      (decode-conversation (tx-query1 tx "SELECT * FROM conversations WHERE conversation_id = ?" conversation-id)))))

(defn append-queued-message!
  "Hold a user message until the current turn finishes. `:id` and
  `:created-at` are filled in when absent; `:llm` is the raw message to feed the
  loop when the queue drains."
  [conversation-id queued]
  (let [entry (merge {:id (new-id) :created-at (iso-time (sql-timestamp))} queued)]
    (mutate-queued! conversation-id #(conj (vec %) entry))))

(defn remove-queued-messages!
  "Drop queued messages by id. Ids that are not present are ignored."
  [conversation-id queued-ids]
  (let [drop-set (set queued-ids)]
    (mutate-queued! conversation-id (fn [msgs] (vec (remove #(contains? drop-set (:id %)) msgs))))))

(defn clear-queued-messages! [conversation-id]
  (mutate-queued! conversation-id (constantly [])))

;; ---------------------------------------------------------------------------
;; Messages: writing

(defn- conversation-exists? [tx id]
  (boolean (tx-query1 tx "SELECT 1 AS ok FROM conversations WHERE conversation_id = ?" id)))

(def ^:private insert-message-sql
  "sequence_id is allocated inside the statement so two appends cannot read the
  same maximum and collide; the generation comes from the conversation for the
  same reason."
  "INSERT INTO messages
     (message_id, conversation_id, sequence_id, generation, type,
      llm_data, user_data, usage_data, display_data, excluded_from_context,
      llm_api_url, model_name, user_email, other_usage_data, external_message_id, created_at)
   VALUES (?, ?,
           (SELECT COALESCE(MAX(sequence_id), 0) + 1 FROM messages WHERE conversation_id = ?),
           COALESCE(?, (SELECT current_generation FROM conversations WHERE conversation_id = ?)),
           ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP))
   RETURNING *")

(defn- append-message-tx! [tx params]
  (let [{:keys [conversation-id message-id type role content llm-data user-data usage-data
                display-data attachments excluded-from-context llm-api-url model-name
                user-email other-usage external-message-id created-at generation
                bump-timestamp remove-queued-id mark-agent-working]} params
        conversation-id (or conversation-id (:id params))
        blocks (or llm-data content)
        blocks (cond (nil? blocks) nil
                     (string? blocks) [(llm/text-block blocks)]
                     :else blocks)
        message-type (if type (role->type type) (if role (role->type role) "user"))
        display (if (seq attachments)
                  (assoc (or display-data {}) :attachments (vec attachments))
                  display-data)
        mid (or message-id (new-id))]
    (when-not (conversation-exists? tx conversation-id)
      (throw (ex-info (str "no such conversation: " conversation-id)
                      {:conversation-id conversation-id})))
    (let [row (tx-query1 tx insert-message-sql
                     mid conversation-id conversation-id
                     generation conversation-id
                     message-type
                     (codec->json blocks) (->json user-data) (->json usage-data) (->json display)
                     (if excluded-from-context 1 0)
                     llm-api-url model-name user-email (->json other-usage) external-message-id
                     created-at)]
      (when (or (nil? bump-timestamp) bump-timestamp)
        (tx-execute! tx "UPDATE conversations SET updated_at = CURRENT_TIMESTAMP WHERE conversation_id = ?"
                  conversation-id))
      (when mark-agent-working
        (tx-execute! tx "UPDATE conversations SET agent_working = TRUE, turn_interrupted = FALSE
                      WHERE conversation_id = ?" conversation-id))
      (when remove-queued-id
        (let [raw (:queued_messages (tx-query1 tx "SELECT queued_messages FROM conversations
                                                WHERE conversation_id = ?" conversation-id))
              msgs (parse-queued-messages raw)
              kept (vec (remove #(= remove-queued-id (:id %)) msgs))]
          (when (= (count kept) (count msgs))
            (throw (ex-info (str "queued message not found: " remove-queued-id)
                            {:conversation-id conversation-id
                             :queued-id remove-queued-id})))
          (write-queued! tx conversation-id kept)))
      (decode-message row))))

(defn append-message!
  "Persist one message. Returns the stored row.

  `:role` (:user/:assistant/:system) or the explicit `:type`
  (`:agent`/`:user`/`:system`/`:error`/`:warning`/`:gitinfo`/`:modelchange`/
  `:slug`) says what the row is; `:content` (or `:llm-data`) is the block
  vector. The rest are optional columns:

    :user-data            UI-only payload (provenance, attachments) as a map
    :display-data         tool-specific rendering data as a map
    :attachments          shorthand for :display-data {:attachments [...]}
    :usage-data           token usage for this call
    :other-usage          usage of indirect LLM calls attributed to this row
    :model-name           what the provider said handled the call
    :llm-api-url          the endpoint the call went to
    :user-email           the account that authored a user message
    :external-message-id  the channel's id for a message from an external chat
    :excluded-from-context keep for accounting, never send to the model
    :generation           defaults to the conversation's current generation
    :created-at           defaults to now
    :remove-queued-id     drain this queued message in the same transaction
    :mark-agent-working   set agent_working=true in the same transaction
    :bump-timestamp       default true"
  [params]
  (jdbc/with-transaction [tx (ds)]
    (append-message-tx! tx params)))

(defn append-messages!
  "Append several messages in one transaction, in order, so exactly one
  timestamp bump happens. All rows must share one conversation."
  [params-list]
  (when (seq params-list)
    (jdbc/with-transaction [tx (ds)]
      (let [rows (mapv #(append-message-tx! tx (assoc % :bump-timestamp false)) params-list)]
        (tx-execute! tx "UPDATE conversations SET updated_at = CURRENT_TIMESTAMP
                      WHERE conversation_id = ?" (:conversation-id (first params-list)))
        rows))))

(defn update-message-user-data!
  "Rewrite one row's user_data. Messages are an append-only log, so this exists
  only for tests of the FTS update trigger; production code appends instead."
  [message-id user-data]
  (execute! "UPDATE messages SET user_data = ? WHERE message_id = ?" (->json user-data) message-id))

;; ---------------------------------------------------------------------------
;; Messages: reading

(defn- decode-messages [rows] (mapv decode-message rows))

(defn messages
  "The model's view of a conversation, in send order: what belongs in the LLM
  context. UI-only rows (error, warning, gitinfo, slug markers) and rows
  excluded from context are left out."
  ([conversation-id] (messages conversation-id {}))
  ([conversation-id {:keys [generation]}]
   (decode-messages
    (query "SELECT m.* FROM messages m
          JOIN conversations c ON c.conversation_id = m.conversation_id
        WHERE m.conversation_id = ? AND m.excluded_from_context = FALSE
          AND m.generation = COALESCE(?, c.current_generation)
          AND m.type IN ('user','agent','system')
        ORDER BY m.sequence_id ASC" conversation-id generation))))

(defn transcript
  "Every row of a conversation, including the ones the model never sees. This
  is what a UI should render; `messages` is what a model should receive.

  opts: :limit :offset (paged, ascending) :since (sequence_id >) :before
        (sequence_id <, newest page first, then flipped to ascending)
        :types (a seq of message types to include)"
  ([conversation-id] (transcript conversation-id {}))
  ([conversation-id {:keys [limit offset since before types]}]
   (let [type-filter (when (seq types)
                       (str " AND m.type IN ("
                            (str/join "," (map (fn [_] "?") types)) ")"))
         type-args (vec (map name types))]
     (cond
       (some? since)
       (decode-messages (apply query (str "SELECT m.* FROM messages m WHERE m.conversation_id = ?
                                        AND m.sequence_id > ?" type-filter
                                     " ORDER BY m.sequence_id ASC")
                              conversation-id since type-args))

       (some? before)
       (->> (apply query (str "SELECT m.* FROM messages m WHERE m.conversation_id = ?
                           AND m.sequence_id < ?" type-filter
                          " ORDER BY m.sequence_id DESC LIMIT ?")
                   (concat [conversation-id before] type-args [(or limit 100)]))
            (decode-messages)
            vec
            reverse
            vec)

       :else
       (decode-messages (apply query (str "SELECT m.* FROM messages m WHERE m.conversation_id = ?"
                                       type-filter
                                      " ORDER BY m.sequence_id ASC LIMIT ? OFFSET ?")
                              (concat [conversation-id] type-args [(or limit -1) (or offset 0)])))))))

(defn message-by-id [message-id]
  (decode-message (query1 "SELECT * FROM messages WHERE message_id = ?" message-id)))

(defn messages-by-type [conversation-id type]
  (decode-messages (query "SELECT * FROM messages WHERE conversation_id = ? AND type = ?
                       ORDER BY sequence_id ASC" conversation-id (name type))))

(defn count-messages
  ([conversation-id]
   (:n (query1 "SELECT COUNT(*) AS n FROM messages WHERE conversation_id = ?" conversation-id)))
  ([conversation-id type]
   (:n (query1 "SELECT COUNT(*) AS n FROM messages WHERE conversation_id = ? AND type = ?"
           conversation-id (name type)))))

(defn messages-since
  "Rows after a sequence id. Deliberately filters no types: this is the client
  cache-repair path, and it must be able to fill any hole."
  [conversation-id sequence-id]
  (decode-messages (query "SELECT * FROM messages WHERE conversation_id = ? AND sequence_id > ?
                       ORDER BY sequence_id ASC" conversation-id sequence-id)))

(defn messages-tail
  "The last `n` visible rows, in ascending order. Invisible bookkeeping inside
  the window is returned too, so clients that detect holes by sequence
  contiguity do not read a marker as missing data."
  [conversation-id n]
  (decode-messages
   (query "SELECT m.* FROM messages m
         WHERE m.conversation_id = ?
           AND m.sequence_id >= COALESCE((
                 SELECT MIN(v.sequence_id) FROM (
                   SELECT vis.sequence_id FROM messages vis
                    WHERE vis.conversation_id = ? AND vis.type NOT IN ('slug','livetranscript')
                    ORDER BY vis.sequence_id DESC LIMIT ?) v), 0)
         ORDER BY m.sequence_id ASC" conversation-id conversation-id n)))

(defn latest-actionable-message
  "The newest row a Retry/Continue affordance could act on. Invisible slug and
  transcript bookkeeping must not disable those affordances."
  [conversation-id]
  (decode-message
   (query1 "SELECT * FROM messages WHERE conversation_id = ?
          AND type NOT IN ('slug','livetranscript')
        ORDER BY sequence_id DESC LIMIT 1" conversation-id)))

(defn generation-at-or-before
  "The generation of the last message at or before a sequence id; a fork must
  copy the generation that was active at its fork point, not the current one."
  [conversation-id sequence-id]
  (:generation (query1 "SELECT generation FROM messages
                     WHERE conversation_id = ? AND sequence_id <= ? AND type != 'slug'
                     ORDER BY sequence_id DESC LIMIT 1" conversation-id sequence-id)))

(defn message-attachments
  "Attachment/image metadata recorded with a message, as
  `{:name :media-type :size ...}` maps. Image bytes themselves live in
  `llm_data` as `{:type :image ...}` blocks; this is the metadata the UI needs
  without decoding them."
  [message-id]
  (let [row (query1 "SELECT display_data, user_data FROM messages WHERE message_id = ?" message-id)]
    (or (:attachments (<-json (:display_data row)))
        (:attachments (<-json (:user_data row)))
        [])))

;; ---------------------------------------------------------------------------
;; Forking

(defn fork-conversation!
  "Copy a conversation up to a sequence id into a new conversation.

  Copies get new message ids and `forked_from_message_id` set, so summing usage
  across a fork does not double-count the original calls. The fork starts a
  fresh generation history, so copies land in generation 1. Slug markers are
  not copied: a fork derives its slug from the source instead of paying for a
  second LLM call."
  [{:keys [source-id cutoff-sequence-id slug title options]}]
  (let [source (or (get-conversation source-id)
                   (throw (ex-info (str "no such conversation: " source-id) {:source-id source-id})))
        cutoff (or cutoff-sequence-id
                   (:seq (query1 "SELECT COALESCE(MAX(sequence_id), 0) AS seq FROM messages
                              WHERE conversation_id = ?" source-id)))
        generation (or (generation-at-or-before source-id cutoff) 1)]
    (jdbc/with-transaction [tx (ds)]
      (let [fork (insert-conversation! tx {:slug slug
                                           :title title
                                           :model (:model source)
                                           :cwd (:cwd source)
                                           :user-initiated (:user-initiated source)
                                           :options (or options (:options source))})]
        (tx-execute! tx "INSERT INTO messages
                        (message_id, conversation_id, sequence_id, generation, type, llm_data,
                         user_data, usage_data, display_data, excluded_from_context, llm_api_url,
                         model_name, user_email, other_usage_data, forked_from_message_id, created_at)
                      SELECT lower(hex(randomblob(16))), ?, m.sequence_id, 1, m.type, m.llm_data,
                             m.user_data, m.usage_data, m.display_data, m.excluded_from_context,
                             m.llm_api_url, m.model_name, m.user_email, m.other_usage_data,
                             m.message_id, m.created_at
                        FROM messages m
                       WHERE m.conversation_id = ? AND m.sequence_id <= ?
                         AND m.generation = ? AND m.type != 'slug'
                       ORDER BY m.sequence_id ASC"
                  (:id fork) source-id cutoff generation)
        ;; Read through the transaction: a separate connection cannot see an
        ;; uncommitted row in WAL mode, and the caller needs the derived fields.
        (decode-conversation (tx-query1 tx "SELECT * FROM conversations WHERE conversation_id = ?"
                                        (:id fork)))))))

;; ---------------------------------------------------------------------------
;; Search

(defn- rune-count [s] (.codePointCount ^String s 0 (.length ^String s)))

(defn fts-match
  "Turn free text into an FTS5 MATCH expression: every term quoted (so user
  input cannot be read as an operator) and ANDed together, with prefix matching
  only for terms of three runes or more — a one-character prefix matches
  everything."
  [search-query]
  (let [terms (remove str/blank? (str/split (str search-query) #"\s+"))]
    (when (seq terms)
      (str/join " AND "
                (map (fn [term]
                       (let [quoted (str "\"" (str/replace term "\"" "\"\"") "\"")]
                         (if (>= (rune-count term) 3) (str quoted "*") quoted)))
                     terms)))))

(defn- like-pattern
  "`%query%` with LIKE's wildcards escaped, so typing a literal % does not match
  every conversation."
  [s]
  (str "%" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "%" "\\%")
                (str/replace "_" "\\_"))
       "%"))

(def ^:private search-conversations-sql
  (str "WITH fts_hits AS (
          SELECT DISTINCT m.conversation_id
            FROM messages_fts
            JOIN messages m ON m.rowid = messages_fts.rowid
            JOIN conversations eligible ON eligible.conversation_id = m.conversation_id
           WHERE messages_fts MATCH ?
             AND eligible.parent_conversation_id IS NULL
             AND eligible.archived = ?
             AND json_extract(eligible.conversation_options, '$.kind') IS NOT 'search'
           ORDER BY eligible.updated_at DESC
           LIMIT ?)
        SELECT c.*, " derived-columns-sql
       ", CAST(CASE WHEN c.slug LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END AS INTEGER) AS slug_rank
          FROM conversations c
         WHERE c.parent_conversation_id IS NULL
           AND c.archived = ?
           AND json_extract(c.conversation_options, '$.kind') IS NOT 'search'
           AND (c.slug LIKE ? ESCAPE '\\' OR c.conversation_id IN (SELECT conversation_id FROM fts_hits))
         ORDER BY slug_rank, c.archived ASC, c.updated_at DESC
         LIMIT ? OFFSET ?"))

(def ^:private search-snippets-sql
  "Best-ranked snippet per conversation. Kept out of the list query so the FTS
  prefix scan starts once, not once per result."
  "WITH ranked AS (
     SELECT m.conversation_id, m.message_id, CAST(m.rowid AS INTEGER) AS message_rowid,
            row_number() OVER (PARTITION BY m.conversation_id ORDER BY bm25(messages_fts)) AS rn
       FROM messages m
       JOIN messages_fts ON messages_fts.rowid = m.rowid
      WHERE messages_fts MATCH ?
        AND m.conversation_id IN (SELECT value FROM json_each(?)))
   SELECT ranked.conversation_id, ranked.message_id,
          snippet(messages_fts, 0, ?, ?, '...', 16) AS snippet
     FROM messages_fts
     JOIN ranked ON ranked.message_rowid = messages_fts.rowid
    WHERE ranked.rn = 1 AND messages_fts MATCH ?")

(def snippet-mark-start "\u0002")
(def snippet-mark-end "\u0003")

(defn- center-on-mark
  "Trim a snippet so the first marked term sits in the leading window the UI
  actually displays. FTS5's token budget collapses on long opaque runs (base64,
  minified JSON) and can push the match off the visible end."
  [s budget]
  (if (<= (count s) budget)
    s
    (let [idx (str/index-of s snippet-mark-start)]
      (cond
        (nil? idx) s
        (<= idx (quot budget 4)) s
        :else (let [start (- idx (quot budget 4))
                    sp (str/index-of s " " start)]
                (str "..." (subs s (if (and sp (< (- sp start) 16)) (inc sp) start))))))))

(defn search-conversations
  "Full-text search over message text, plus a slug substring match.

  Slug matches come first, then results by recency. Each hit carries a
  `:snippet` with `\\u0002`/`\\u0003` sentinels around the matched terms (instead
  of HTML, so nothing in a message body can be executed by the UI) and
  `:snippet-message-id`. Slug-only hits have no snippet.

  opts: :archived (false) :limit (50) :offset (0)"
  ([search-query] (search-conversations search-query {}))
  ([search-query {:keys [archived limit offset] :or {limit 50 offset 0}}]
   (if-let [match (fts-match search-query)]
     (let [pattern (like-pattern search-query)
           archived-flag (if archived 1 0)
           rows (query search-conversations-sql
                   match archived-flag (+ (long limit) (long offset))
                   pattern archived-flag pattern
                   limit offset)
           hits (mapv decode-list-item rows)
           ids (mapv :id hits)]
       (if (empty? ids)
         []
         (let [snippets (->> (query search-snippets-sql match (json/generate-string ids)
                               snippet-mark-start snippet-mark-end match)
                             (reduce (fn [acc row]
                                       (assoc acc (:conversation_id row)
                                              {:snippet (center-on-mark (:snippet row) 120)
                                               :message-id (:message_id row)}))
                                     {}))]
           (mapv (fn [hit]
                   (let [{:keys [snippet message-id]} (get snippets (:id hit))]
                     (assoc hit :snippet (or snippet "") :snippet-message-id message-id)))
                 hits))))
     [])))

(defn search-messages
  "Per-message full-text hits, best first.

  opts: :limit (50) :offset (0) :conversation-id (restrict to one conversation)"
  ([search-query] (search-messages search-query {}))
  ([search-query {:keys [limit offset conversation-id] :or {limit 50 offset 0}}]
   (if-let [match (fts-match search-query)]
     (->> (if conversation-id
            (query (str "SELECT m.*, snippet(messages_fts, 0, ?, ?, '...', 24) AS snippet,
                            bm25(messages_fts) AS rank
                       FROM messages_fts JOIN messages m ON m.rowid = messages_fts.rowid
                      WHERE messages_fts MATCH ? AND m.conversation_id = ?
                      ORDER BY rank LIMIT ? OFFSET ?")
               snippet-mark-start snippet-mark-end match conversation-id limit offset)
            (query (str "SELECT m.*, snippet(messages_fts, 0, ?, ?, '...', 24) AS snippet,
                            bm25(messages_fts) AS rank
                       FROM messages_fts JOIN messages m ON m.rowid = messages_fts.rowid
                      WHERE messages_fts MATCH ?
                      ORDER BY rank LIMIT ? OFFSET ?")
               snippet-mark-start snippet-mark-end match limit offset))
          (mapv (fn [row] (assoc (decode-message row) :snippet (:snippet row) :rank (:rank row)))))
     [])))

;; ---------------------------------------------------------------------------
;; Iframes (clj-shelley only: rendered artefacts served back to the browser)

(declare get-iframe)

(defn save-iframe! [{:keys [conversation-id id title html]}]
  (execute! "INSERT OR REPLACE INTO iframes (id, conversation_id, title, html, created_at)
          VALUES (?,?,?,?,?)"
         id conversation-id title html (sql-timestamp))
  (get-iframe id))

(defn get-iframe [id]
  (query1 "SELECT * FROM iframes WHERE id = ?" id))

(defn list-iframes [conversation-id]
  (query "SELECT * FROM iframes WHERE conversation_id = ? ORDER BY created_at ASC" conversation-id))

;; ---------------------------------------------------------------------------
;; Settings and feature flags

(defn get-setting [k]
  (:value (query1 "SELECT value FROM settings WHERE key = ?" (snake-key k))))

(defn set-setting! [k v]
  (execute! "INSERT INTO settings (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)
          ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP"
         (snake-key k) (str v))
  v)

(defn init-setting!
  "Set the value only when the key is absent. Returns the value in force."
  [k v]
  (execute! "INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)" (snake-key k) (str v))
  (get-setting k))

(defn all-settings []
  (into {} (map (fn [row] [(keyword (str/replace (:key row) "_" "-")) (:value row)]))
        (query "SELECT key, value FROM settings")))

(defn delete-setting! [k]
  (affected (execute! "DELETE FROM settings WHERE key = ?" (snake-key k))))

(defn feature-flags
  "Flag name -> decoded value. Only names the code recognises are meaningful;
  deleting a row reverts to the code default."
  []
  (into {} (map (fn [row] [(keyword (:name row)) (<-json (:value row))]))
        (query "SELECT name, value FROM feature_flags")))

(defn set-feature-flag! [flag value]
  (execute! "INSERT INTO feature_flags (name, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)
          ON CONFLICT(name) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP"
         (clojure.core/name flag) (->json value))
  value)

(defn delete-feature-flag! [flag]
  (affected (execute! "DELETE FROM feature_flags WHERE name = ?" (clojure.core/name flag))))

;; ---------------------------------------------------------------------------
;; Custom models and providers

(defn- decode-model [row]
  (when row
    (assoc row
           :model-id (:model_id row)
           :display-name (:display_name row)
           :provider-type (:provider_type row)
           :model-name (:model_name row)
           :max-tokens (:max_tokens row)
           :reasoning-effort (:reasoning_effort row)
           :image-support (:image_support row)
           :reasoning-support (:reasoning_support row)
           :reasoning-map (:reasoning_map row)
           :reasoning-replay (:reasoning_replay row)
           :created-at (iso-time (:created_at row))
           :updated-at (iso-time (:updated_at row)))))

(defn list-models []
  (mapv decode-model (query "SELECT * FROM models ORDER BY created_at ASC")))

(defn get-model [model-id]
  (decode-model (query1 "SELECT * FROM models WHERE model_id = ?" model-id)))

(defn save-model!
  "Insert or update a custom model. `:model-id` identifies it."
  [{:keys [model-id display-name provider-type endpoint api-key model-name max-tokens
           reasoning-effort image-support reasoning-support reasoning-map reasoning-replay]}]
  (execute! "INSERT INTO models (model_id, display_name, provider_type, endpoint, api_key, model_name,
                               max_tokens, reasoning_effort, image_support, reasoning_support,
                               reasoning_map, reasoning_replay)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(model_id) DO UPDATE SET
               display_name = excluded.display_name, provider_type = excluded.provider_type,
               endpoint = excluded.endpoint, api_key = excluded.api_key,
               model_name = excluded.model_name, max_tokens = excluded.max_tokens,
               reasoning_effort = excluded.reasoning_effort, image_support = excluded.image_support,
               reasoning_support = excluded.reasoning_support, reasoning_map = excluded.reasoning_map,
               reasoning_replay = excluded.reasoning_replay, updated_at = CURRENT_TIMESTAMP"
         model-id display-name provider-type endpoint api-key model-name
         (or max-tokens 200000) (or reasoning-effort "") (or image-support "auto")
         (or reasoning-support "auto") (or reasoning-map "") (or reasoning-replay "auto"))
  (get-model model-id))

(defn delete-model! [model-id]
  (affected (execute! "DELETE FROM models WHERE model_id = ?" model-id)))

(defn- decode-provider [row]
  (when row
    (assoc row
           :provider-id (:provider_id row)
           :provider-type (:provider_type row)
           :display-name (:display_name row)
           :models (or (<-json (:models row)) []))))

(defn list-model-providers []
  (mapv decode-provider (query "SELECT * FROM model_providers ORDER BY provider_id")))

(defn get-model-provider [provider-id]
  (decode-provider (query1 "SELECT * FROM model_providers WHERE provider_id = ?" provider-id)))

(defn save-model-provider!
  "A provider groups several custom models behind one endpoint and key."
  [{:keys [provider-id provider-type endpoint api-key display-name models version]}]
  (execute! "INSERT INTO model_providers
             (provider_id, provider_type, endpoint, api_key, display_name, models, version)
           VALUES (?,?,?,?,?,?,?)
           ON CONFLICT(provider_id) DO UPDATE SET
               provider_type = excluded.provider_type, endpoint = excluded.endpoint,
               api_key = excluded.api_key, display_name = excluded.display_name,
               models = excluded.models, version = excluded.version"
         provider-id provider-type endpoint api-key display-name
         (json/generate-string (vec models)) (or version 1))
  (get-model-provider provider-id))

(defn delete-model-provider! [provider-id]
  (affected (execute! "DELETE FROM model_providers WHERE provider_id = ?" provider-id)))

;; ---------------------------------------------------------------------------
;; Profiles

(defn- decode-profile [row]
  (when row
    (assoc row
           :is-default (->bool (:is_default row))
           :builtin (->bool (:builtin row))
           :user-edited (->bool (:user_edited row))
           :settings (or (<-json (:settings row)) {}))))

(defn list-profiles []
  (mapv decode-profile (query "SELECT * FROM profiles ORDER BY is_default DESC, name")))

(defn get-profile [name]
  (decode-profile (query1 "SELECT * FROM profiles WHERE name = ?" (str name))))

(defn default-profile []
  (decode-profile (query1 "SELECT * FROM profiles WHERE is_default")))

(defn create-profile! [name settings]
  (execute! "INSERT OR IGNORE INTO profiles (name, settings) VALUES (?, ?)"
         (str name) (or (->json settings) "{}"))
  (get-profile name))

(defn update-profile-settings! [name settings]
  (decode-profile
   (query1 "UPDATE profiles SET settings = ?, user_edited = TRUE WHERE name = ? RETURNING *"
       (or (->json settings) "{}") (str name))))

(defn set-default-profile!
  "Exactly one profile is the default; the unique index enforces it, so this
  clears first and sets second in one transaction."
  [name]
  (jdbc/with-transaction [tx (ds)]
    (tx-execute! tx "UPDATE profiles SET is_default = FALSE WHERE is_default")
    (tx-execute! tx "UPDATE profiles SET is_default = TRUE WHERE name = ?" (str name)))
  (get-profile name))

(defn delete-profile! [name]
  (affected (execute! "DELETE FROM profiles WHERE name = ? AND NOT is_default" (str name))))

(defn builtin-profiles []
  (mapv :name (query "SELECT name FROM builtin_profiles ORDER BY name")))

(defn builtin-profile? [name]
  (boolean (query1 "SELECT 1 AS ok FROM builtin_profiles WHERE name = ?" (str name))))

(defn register-builtin-profile!
  "Built-ins are registered separately from `profiles` so deleting one does not
  reinstall it on the next start."
  [name]
  (execute! "INSERT OR IGNORE INTO builtin_profiles (name) VALUES (?)" (str name)))

(defn create-builtin-profile! [name settings]
  (execute! "INSERT OR IGNORE INTO profiles (name, settings, builtin, user_edited)
          VALUES (?, ?, TRUE, FALSE)"
         (str name) (or (->json settings) "{}"))
  (get-profile name))

(defn restore-builtin-profile! [name settings]
  (decode-profile
   (query1 "UPDATE profiles SET settings = ?, user_edited = FALSE WHERE name = ? AND builtin RETURNING *"
       (or (->json settings) "{}") (str name))))

;; ---------------------------------------------------------------------------
;; Notification channels

(defn- decode-channel [row]
  (when row
    (assoc row
           :channel-id (:channel_id row)
           :channel-type (:channel_type row)
           :display-name (:display_name row)
           :enabled (->bool (:enabled row))
           :config (or (<-json (:config row)) {}))))

(defn list-notification-channels
  ([] (mapv decode-channel (query "SELECT * FROM notification_channels ORDER BY created_at ASC")))
  ([{:keys [enabled-only?]}]
   (if enabled-only?
     (mapv decode-channel (query "SELECT * FROM notification_channels WHERE enabled = 1 ORDER BY created_at ASC"))
     (list-notification-channels))))

(defn get-notification-channel [channel-id]
  (decode-channel (query1 "SELECT * FROM notification_channels WHERE channel_id = ?" channel-id)))

(defn save-notification-channel!
  [{:keys [channel-id channel-type display-name enabled config]}]
  (let [channel-id (or channel-id (new-id))]
    (execute! "INSERT INTO notification_channels
               (channel_id, channel_type, display_name, enabled, config)
             VALUES (?,?,?,?,?)
             ON CONFLICT(channel_id) DO UPDATE SET
                 display_name = excluded.display_name, enabled = excluded.enabled,
                 config = excluded.config, updated_at = CURRENT_TIMESTAMP"
           channel-id channel-type display-name (if (false? enabled) 0 1) (or (->json config) "{}"))
    (get-notification-channel channel-id)))

(defn delete-notification-channel! [channel-id]
  (affected (execute! "DELETE FROM notification_channels WHERE channel_id = ?" channel-id)))

;; ---------------------------------------------------------------------------
;; Cache sessions

(defn get-cache-session [token-hash]
  (query1 "SELECT * FROM cache_sessions WHERE token_hash = ?" token-hash))

(defn upsert-cache-session! [token-hash user-id]
  (execute! "INSERT INTO cache_sessions (token_hash, user_id, created_at, last_seen_at)
          VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
          ON CONFLICT(token_hash) DO UPDATE SET
              user_id = excluded.user_id, last_seen_at = CURRENT_TIMESTAMP"
         token-hash user-id))

(defn delete-cache-session! [token-hash]
  (affected (execute! "DELETE FROM cache_sessions WHERE token_hash = ?" token-hash)))

;; ---------------------------------------------------------------------------
;; MCP servers

(defn- decode-mcp-server [row]
  (when row
    (assoc row
           :display-name (:name row)
           :headers (or (<-json (:headers row)) {}))))

(defn list-mcp-servers []
  (mapv decode-mcp-server (query "SELECT * FROM mcp_servers ORDER BY name")))

(defn get-mcp-server [name]
  (decode-mcp-server (query1 "SELECT * FROM mcp_servers WHERE name = ?" name)))

(defn save-mcp-server!
  [{:keys [name url description headers]}]
  (execute! "INSERT INTO mcp_servers (name, url, description, headers) VALUES (?,?,?,?)
          ON CONFLICT(name) DO UPDATE SET
              url = excluded.url, description = excluded.description,
              headers = excluded.headers, updated_at = CURRENT_TIMESTAMP"
         name url (or description "") (or (->json headers) "{}"))
  (get-mcp-server name))

(defn delete-mcp-server! [name]
  (affected (execute! "DELETE FROM mcp_servers WHERE name = ?" name)))

(defn get-mcp-oauth [server-name]
  (when-let [row (query1 "SELECT * FROM mcp_oauth WHERE server_name = ?" server-name)]
    (assoc row :server-name (:server_name row)
               :client (<-json (:client row))
               :token (<-json (:token row)))))

(defn save-mcp-oauth! [server-name client token]
  (execute! "INSERT INTO mcp_oauth (server_name, client, token) VALUES (?,?,?)
          ON CONFLICT(server_name) DO UPDATE SET
              client = excluded.client, token = excluded.token,
              updated_at = CURRENT_TIMESTAMP"
         server-name (json/generate-string (snake-keys client)) (json/generate-string (snake-keys token)))
  (get-mcp-oauth server-name))

(defn delete-mcp-oauth! [server-name]
  (affected (execute! "DELETE FROM mcp_oauth WHERE server_name = ?" server-name)))

;; ---------------------------------------------------------------------------
;; Background jobs

(defn- decode-job [row]
  (when row
    (assoc row
           :job-id (:job_id row)
           :conversation-id (:conversation_id row)
           :tool-use-id (:tool_use_id row)
           :process-start-time (:process_start_time row)
           :log-path (:log_path row)
           :exit-path (:exit_path row)
           :started-at (iso-time (:started_at row))
           :notified (->bool (:notified row))
           :exited (->bool (:exited row)))))

(defn save-background-job!
  "Record a bash command that was moved to the background. Jobs outlive the
  process, so the record is the only way to report one after a restart."
  [{:keys [job-id conversation-id tool-use-id command pid process-start-time
           log-path exit-path started-at]}]
  (let [job-id (or job-id (new-id))]
    (execute! "INSERT INTO background_jobs
               (job_id, conversation_id, tool_use_id, command, pid, process_start_time,
                log_path, exit_path, started_at)
             VALUES (?,?,?,?,?,?,?,?,COALESCE(?, CURRENT_TIMESTAMP))"
           job-id conversation-id tool-use-id command pid
           (or process-start-time 0) log-path exit-path started-at)
    (decode-job (query1 "SELECT * FROM background_jobs WHERE job_id = ?" job-id))))

(defn list-running-background-jobs [conversation-id]
  (mapv decode-job (query "SELECT * FROM background_jobs WHERE conversation_id = ? AND NOT exited
                       ORDER BY started_at" conversation-id)))

(defn list-unfinished-background-jobs
  "Jobs to report after a restart: still running, or finished but not yet told
  to the conversation."
  []
  (mapv decode-job (query "SELECT * FROM background_jobs WHERE NOT exited OR NOT notified
                       ORDER BY started_at")))

(defn mark-background-job-exited!
  "Returns the job's `notified` flag, or nil when it was already exited."
  [job-id]
  (when-let [row (query1 "UPDATE background_jobs SET exited = TRUE WHERE job_id = ? AND NOT exited
                          RETURNING notified" job-id)]
    (->bool (:notified row))))

(defn mark-background-job-notified!
  "Notified implies exited: a job nobody is waiting on any more has ended."
  [job-id]
  (execute! "UPDATE background_jobs SET notified = TRUE, exited = TRUE WHERE job_id = ?" job-id))

;; ---------------------------------------------------------------------------
;; Live calls

(defn create-live-call!
  "Number a conversation's Live calls 1, 2, ..."
  [conversation-id]
  (jdbc/with-transaction [tx (ds)]
    (:call (tx-query1 tx "INSERT INTO live_calls (conversation_id, call)
                      SELECT ?, COALESCE(MAX(call), 0) + 1 FROM live_calls WHERE conversation_id = ?
                      RETURNING call"
                  conversation-id conversation-id))))

(defn append-live-call-transcript!
  [{:keys [conversation-id call n user text start-ms end-ms]}]
  (execute! "INSERT INTO live_call_transcripts (conversation_id, call, n, user, text, start_ms, end_ms)
          VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING"
         conversation-id call n (if user 1 0) text start-ms end-ms))

(defn live-call-transcript [conversation-id call]
  (mapv (fn [row]
          (assoc row
                 :conversation-id (:conversation_id row)
                 :start-ms (:start_ms row)
                 :end-ms (:end_ms row)
                 :user (->bool (:user row))))
        (query "SELECT * FROM live_call_transcripts WHERE conversation_id = ? AND call = ? ORDER BY n"
               conversation-id call)))
