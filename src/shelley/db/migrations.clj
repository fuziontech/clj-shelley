(ns shelley.db.migrations
  "Schema migrations, applied by name.

  The runner mirrors the Go original (db/schema/*.sql): numbered, named files
  recorded by *filename* in a `migrations` table, each applied exactly once
  inside its own transaction, in (number, name) order. Recording the name and
  not just the number is deliberate: two feature branches routinely both take
  \"the next number\", and a renumber-on-rebase must not make the runner skip a
  migration that a database still needs (that is the bug 028-migrations-by-name
  fixed upstream).

  Migrations are additive in intent — they create tables, add columns, add
  indexes — because the database holds real transcripts that cannot be
  regenerated. The one destructive-looking step is `adopt-legacy!`, which
  rebuilds the pre-migrations clj-shelley tables (`conversations.id` /
  `working_dir`, `messages.role` / `content`) into the current shape while
  copying every row across.

  **Names are the upstream names.** Every file here mirrors one file in the
  original's `db/schema/`, with the same number and filename, so the two
  histories can be read side by side. Where the original achieved a change by
  rebuilding a table and SQLite lets us do it in place (or where the change does
  not apply to us at all), the file is present with a `:note` and no statements
  — the runner logs it, and the gap is visible rather than papered over. Files
  whose names start at 900 are clj-shelley's own and have no upstream
  counterpart.

  Statements may be SQL strings or functions of the transaction. Functions are
  used where SQLite has no `IF NOT EXISTS` (ADD COLUMN, DROP COLUMN), so that a
  database whose migrations table was lost is repaired instead of failing.
  Every migration is idempotent for that reason, and because this project
  previously numbered its own migrations: see `legacy-migration-names`."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private rs-opts {:builder-fn rs/as-unqualified-lower-maps})

;; ---------------------------------------------------------------------------
;; Statement helpers

(defn- table-columns [tx table]
  (->> (jdbc/execute! tx [(str "PRAGMA table_info(" table ")")] rs-opts)
       (mapv :name)))

(defn- column-exists? [tx table col]
  (boolean (some #{col} (table-columns tx table))))

(defn- table-exists? [tx table]
  (boolean (:n (jdbc/execute-one!
                tx ["SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table' AND name = ?" table]
                rs-opts))))

(defn add-column!
  "`ALTER TABLE ADD COLUMN`, skipped when the column is already there.

  SQLite has no `IF NOT EXISTS` for ADD COLUMN, and this is the one place where
  re-running a migration is harmless rather than wrong — a database that lost
  only its migrations rows gets repaired instead of blowing up at startup."
  [table col ddl]
  (fn [tx]
    (when-not (column-exists? tx table col)
      (jdbc/execute! tx [(str "ALTER TABLE " table " ADD COLUMN " col " " ddl)]))))

(defn drop-column!
  "`ALTER TABLE DROP COLUMN`, skipped when the column is already gone. SQLite
  only gained DROP COLUMN in 3.35, and the column may never have existed."
  [table col]
  (fn [tx]
    (when (column-exists? tx table col)
      (jdbc/execute! tx [(str "ALTER TABLE " table " DROP COLUMN " col)]))))

(defn- index! [name ddl] (str "CREATE INDEX IF NOT EXISTS " name " " ddl))
(defn- unique-index! [name ddl] (str "CREATE UNIQUE INDEX IF NOT EXISTS " name " " ddl))

;; ---------------------------------------------------------------------------
;; Full-text extraction

(defn- json-or-null
  "json_tree() raises on malformed JSON; 'null' has no keys to match, so a
  corrupt or NULL column indexes as empty text instead of failing the INSERT
  that wrote it."
  [expr]
  (str "(CASE WHEN json_valid(" expr ") THEN " expr " ELSE 'null' END)"))

(defn- fts-text-expr
  "Searchable text of one message: every Text/Thinking value anywhere in the
  block JSON. llm_data wins when it has any; user_data is the fallback for
  UI-only rows. Indexing the raw JSON instead would bury matches inside tool
  input, base64 image data and structural keys, as 021-messages-fts notes."
  [llm-expr user-expr]
  (let [extract (fn [expr]
                  (str "(SELECT group_concat(t.value, ' ') FROM json_tree(" (json-or-null expr) ") t"
                       " WHERE t.key IN ('text','thinking') AND t.type = 'text' AND length(t.value) > 0)"))]
    (str "COALESCE(" (extract llm-expr) ", " (extract user-expr) ", '')")))

(defn- fts-triggers
  "Three triggers keep messages_fts in step with messages. FTS5 has no external
  content table here (the text is a projection of message JSON, not a column),
  so deletes and updates are explicit delete + reinsert keyed on rowid."
  []
  [(str "CREATE TRIGGER IF NOT EXISTS messages_fts_ai AFTER INSERT ON messages
         WHEN new.type IN ('user','agent') BEGIN
           INSERT INTO messages_fts(rowid, text)
           VALUES (new.rowid, " (fts-text-expr "new.llm_data" "new.user_data") ");
         END")
   (str "CREATE TRIGGER IF NOT EXISTS messages_fts_ad AFTER DELETE ON messages
         WHEN old.type IN ('user','agent') BEGIN
           DELETE FROM messages_fts WHERE rowid = old.rowid;
         END")
   (str "CREATE TRIGGER IF NOT EXISTS messages_fts_au AFTER UPDATE ON messages
         WHEN old.type IN ('user','agent') OR new.type IN ('user','agent') BEGIN
           DELETE FROM messages_fts WHERE rowid = old.rowid;
           INSERT INTO messages_fts(rowid, text)
           SELECT new.rowid, " (fts-text-expr "new.llm_data" "new.user_data") "
            WHERE new.type IN ('user','agent');
         END")])


;; ---------------------------------------------------------------------------
;; The migrations
;;
;; One entry per upstream file, in upstream order. `:note` marks a file that
;; carries no statements here and says why; the runner logs those when it
;; applies them so an operator sees the same history upstream would print.

(def ^:private migrations
  [{:number 1
    :name "001-conversations.sql"
    :statements
    [;; The runner creates this table in its final (028) shape before anything
     ;; else runs, so this statement never executes; it is kept so the file
     ;; reads like the upstream one.
     "CREATE TABLE IF NOT EXISTS migrations (
         migration_number INTEGER PRIMARY KEY,
         migration_name TEXT NOT NULL,
         executed_at DATETIME DEFAULT CURRENT_TIMESTAMP)"
     "CREATE TABLE IF NOT EXISTS conversations (
         conversation_id TEXT PRIMARY KEY,
         slug TEXT,                       -- human-readable identifier, null until generated
         user_initiated BOOLEAN NOT NULL DEFAULT TRUE,  -- FALSE for subagent/tool conversations
         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"
     (unique-index! "idx_conversations_slug_unique" "ON conversations(slug) WHERE slug IS NOT NULL")
     (index! "idx_conversations_updated_at" "ON conversations(updated_at DESC)")]}

   {:number 2
    :name "002-messages.sql"
    :statements
    [;; Upstream creates messages here and rebuilds it in 003 to add
     ;; sequence_id (SQLite cannot add a NOT NULL column in place). We create
     ;; the post-003 shape directly, which is also what 003 then finds.
     "CREATE TABLE IF NOT EXISTS messages (
         message_id TEXT PRIMARY KEY,
         conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
         sequence_id INTEGER NOT NULL,     -- ordering within a conversation
         type TEXT NOT NULL,               -- user/agent/tool/system/error/... application-level enum
         llm_data TEXT,                    -- JSON: content blocks sent to / received from the model
         user_data TEXT,                   -- JSON: UI-only payload
         usage_data TEXT,                  -- JSON: token usage for this call
         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"
     (index! "idx_messages_conversation_id" "ON messages(conversation_id)")
     (index! "idx_messages_conversation_sequence" "ON messages(conversation_id, sequence_id)")
     (index! "idx_messages_type" "ON messages(type)")]}

   {:number 3
    :name "003-add-message-sequence.sql"
    :note "sequence_id is created with the table in 002; nothing to rebuild"}

   {:number 4
    :name "004-add-error-message-type.sql"
    :note "upstream rebuilds messages to widen a CHECK constraint; we never create one (see 022)"}

   {:number 5
    :name "005-add-display-data.sql"
    :statements [(add-column! "messages" "display_data" "TEXT")]}

   {:number 6
    :name "006-add-cwd.sql"
    :statements [(add-column! "conversations" "cwd" "TEXT")]}

   {:number 7
    :name "007-add-archived.sql"
    :statements [(add-column! "conversations" "archived" "BOOLEAN NOT NULL DEFAULT FALSE")
                 (index! "idx_conversations_archived" "ON conversations(archived)")]}

   {:number 8
    :name "008-add-gitinfo-message-type.sql"
    :note "upstream rebuilds messages to widen a CHECK constraint; we never create one (see 022)"}

   {:number 9
    :name "009-add-parent-conversation.sql"
    :statements [(add-column! "conversations" "parent_conversation_id"
                              "TEXT REFERENCES conversations(conversation_id)")
                 (index! "idx_conversations_parent_id"
                         "ON conversations(parent_conversation_id) WHERE parent_conversation_id IS NOT NULL")]}

   {:number 10
    :name "010-add-llm-requests.sql"
    :note "llm_requests (the removed debug transcript table) is not implemented in clj-shelley"}

   {:number 11
    :name "011-add-llm-request-prefix.sql"
    :note "llm_requests is not implemented in clj-shelley"}

   {:number 12
    :name "012-custom-models.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS models (
         model_id TEXT PRIMARY KEY,
         display_name TEXT NOT NULL,
         provider_type TEXT NOT NULL
             CHECK (provider_type IN ('anthropic', 'openai', 'openai-responses', 'gemini')),
         endpoint TEXT NOT NULL,
         api_key TEXT NOT NULL,
         model_name TEXT NOT NULL,        -- the name sent on the wire
         max_tokens INTEGER NOT NULL DEFAULT 200000,
         tags TEXT NOT NULL DEFAULT '',   -- dropped again in 048
         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"]}

   {:number 13
    :name "013-add-model.sql"
    :statements [(add-column! "conversations" "model" "TEXT")]}

   {:number 14
    :name "014-add-excluded-from-context.sql"
    :statements [(add-column! "messages" "excluded_from_context" "BOOLEAN NOT NULL DEFAULT FALSE")]}

   {:number 15
    :name "015-notification-channels.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS notification_channels (
         channel_id TEXT PRIMARY KEY,
         channel_type TEXT NOT NULL,
         display_name TEXT NOT NULL,
         enabled INTEGER NOT NULL DEFAULT 1,
         config TEXT NOT NULL DEFAULT '{}',
         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"]}

   {:number 16
    :name "016-settings.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS settings (
         key TEXT PRIMARY KEY,
         value TEXT NOT NULL,
         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"]}

   {:number 17
    :name "017-add-conversation-options.sql"
    :statements [(add-column! "conversations" "conversation_options" "TEXT NOT NULL DEFAULT '{}'")]}

   {:number 18
    :name "018-add-reasoning-effort.sql"
    :statements [(add-column! "models" "reasoning_effort" "TEXT NOT NULL DEFAULT ''")]}

   {:number 19
    :name "019-add-conversation-generations.sql"
    :statements
    [(add-column! "conversations" "current_generation" "INTEGER NOT NULL DEFAULT 1")
     (add-column! "messages" "generation" "INTEGER NOT NULL DEFAULT 1")
     (index! "idx_messages_conversation_generation_context_sequence"
             "ON messages(conversation_id, generation, excluded_from_context, sequence_id)")]}

   {:number 20
    :name "020-add-agent-working.sql"
    :statements [(add-column! "conversations" "agent_working" "BOOLEAN NOT NULL DEFAULT FALSE")]}

   {:number 21
    :name "021-messages-fts.sql"
    :statements
    (into
     [;; Deviation from upstream, documented because it is the point of the
      ;; file: their blocks are Go structs marshalled with capitalised keys
      ;; ("Text"/"Thinking") and they read `coalesce(user_data, llm_data)`.
      ;; Ours are Clojure maps with lower-case kebab keys, and llm_data is the
      ;; block vector, so we look for "text"/"thinking" and prefer llm_data,
      ;; falling back to user_data for UI-only rows.
      "CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(text, tokenize='porter unicode61')"
      (str "INSERT INTO messages_fts(rowid, text)
            SELECT m.rowid, " (fts-text-expr "m.llm_data" "m.user_data") "
              FROM messages m
             WHERE m.type IN ('user','agent')
               AND NOT EXISTS (SELECT 1 FROM messages_fts f WHERE f.rowid = m.rowid)")]
     (fts-triggers))}

   {:number 22
    :name "022-drop-message-type-check-constraint.sql"
    :note "we never create a type CHECK constraint, so there is nothing to rebuild"}

   {:number 23
    :name "023-cache-sessions.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS cache_sessions (
         token_hash TEXT PRIMARY KEY,     -- SHA-256 of the cookie; the cookie is never stored
         user_id TEXT NOT NULL,
         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
         last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"
     (index! "idx_cache_sessions_last_seen" "ON cache_sessions(last_seen_at)")]}

   {:number 24
    :name "024-feature-flags.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS feature_flags (
         name TEXT PRIMARY KEY,
         value TEXT NOT NULL,             -- JSON-encoded value
         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"]}

   {:number 25
    :name "025-drop-llm-requests.sql"
    :note "llm_requests was never created here (see 010)"}

   {:number 26
    :name "026-add-model-image-support.sql"
    :statements [(add-column! "models" "image_support" "TEXT NOT NULL DEFAULT 'auto'")]}

   {:number 27
    :name "027-add-tags.sql"
    :statements [(add-column! "conversations" "tags" "TEXT NOT NULL DEFAULT '[]'")]}

   {:number 28
    :name "028-migrations-by-name.sql"
    :statements
    [;; Upstream rebuilds the table because theirs was keyed by
     ;; migration_number. The runner already creates the final shape, so this
     ;; only rebuilds a database that predates it — which is precisely the
     ;; database this migration exists for.
     (fn [tx]
       (when (and (table-exists? tx "migrations")
                  (not (column-exists? tx "migrations" "id")))
         (jdbc/execute! tx ["CREATE TABLE migrations_by_name (
                               id INTEGER PRIMARY KEY AUTOINCREMENT,
                               migration_number INTEGER NOT NULL,
                               migration_name TEXT NOT NULL UNIQUE,
                               executed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"])
         (jdbc/execute! tx ["INSERT INTO migrations_by_name (migration_number, migration_name, executed_at)
                             SELECT migration_number, migration_name, executed_at FROM migrations"])
         (jdbc/execute! tx ["DROP TABLE migrations"])
         (jdbc/execute! tx ["ALTER TABLE migrations_by_name RENAME TO migrations"])))]}

   {:number 29
    :name "029-messages-conv-type-seq-index.sql"
    :statements
    [(index! "idx_messages_conv_type_seq" "ON messages(conversation_id, type, sequence_id)")
     "ANALYZE"]}

   {:number 30
    :name "030-add-drafts.sql"
    :statements [(add-column! "conversations" "is_draft" "BOOLEAN NOT NULL DEFAULT FALSE")
                 (add-column! "conversations" "draft" "TEXT NOT NULL DEFAULT ''")]}

   {:number 31
    :name "031-add-message-model.sql"
    :statements [(add-column! "messages" "llm_api_url" "TEXT")
                 (add-column! "messages" "model_name" "TEXT")]}

   {:number 32
    :name "032-add-message-forked-from.sql"
    :statements
    [;; Deliberately no FK: a fork must survive deletion of its source.
     (add-column! "messages" "forked_from_message_id" "TEXT")
     (index! "idx_messages_forked_from"
             "ON messages(forked_from_message_id) WHERE forked_from_message_id IS NOT NULL")]}

   {:number 33
    :name "033-add-queued-messages.sql"
    :statements [(add-column! "conversations" "queued_messages" "TEXT NOT NULL DEFAULT '[]'")]}

   {:number 34
    :name "034-add-model-reasoning-config.sql"
    :statements [(add-column! "models" "reasoning_support" "TEXT NOT NULL DEFAULT 'auto'")
                 (add-column! "models" "reasoning_map" "TEXT NOT NULL DEFAULT ''")]}

   {:number 35
    :name "035-add-message-user-email.sql"
    :statements [(add-column! "messages" "user_email" "TEXT")]}

   {:number 36
    :name "036-message-other-usage.sql"
    :statements
    ["DROP TABLE IF EXISTS llm_usage"
     (add-column! "messages" "other_usage_data" "TEXT")]}

   {:number 37
    :name "037-messages-participants-index.sql"
    :statements
    [(index! "idx_messages_participants"
             "ON messages(conversation_id, user_email) WHERE user_email IS NOT NULL AND user_email <> ''")
     "ANALYZE idx_messages_participants"]}

   {:number 38
    :name "038-add-turn-interrupted.sql"
    :statements [(add-column! "conversations" "turn_interrupted" "BOOLEAN NOT NULL DEFAULT FALSE")]}

   {:number 39
    :name "039-add-model-reasoning-replay.sql"
    :statements [(add-column! "models" "reasoning_replay" "TEXT NOT NULL DEFAULT 'auto'")]}

   {:number 40
    :name "040-add-background-jobs.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS background_jobs (
         job_id TEXT PRIMARY KEY,
         conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
         tool_use_id TEXT NOT NULL,
         command TEXT NOT NULL,
         pid INTEGER NOT NULL,
         process_start_time INTEGER NOT NULL,
         log_path TEXT NOT NULL,
         exit_path TEXT NOT NULL,
         started_at DATETIME NOT NULL,
         notified BOOLEAN NOT NULL DEFAULT FALSE)"]}

   {:number 41
    :name "041-mcp.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS mcp_servers (
         name TEXT PRIMARY KEY,
         url TEXT NOT NULL,
         description TEXT NOT NULL DEFAULT '',
         headers TEXT NOT NULL DEFAULT '{}',
         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"
     "CREATE TABLE IF NOT EXISTS mcp_oauth (
         server_name TEXT PRIMARY KEY REFERENCES mcp_servers(name) ON DELETE CASCADE,
         client TEXT NOT NULL,           -- JSON oauth2 client registration
         token TEXT NOT NULL,            -- JSON oauth2 token
         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)"]}

   {:number 42
    :name "042-background-jobs-exited.sql"
    :statements
    [(add-column! "background_jobs" "exited" "BOOLEAN NOT NULL DEFAULT FALSE")
     "UPDATE background_jobs SET exited = TRUE WHERE notified"
     (index! "idx_background_jobs_running"
             "ON background_jobs(conversation_id, started_at) WHERE NOT exited")]}

   {:number 43
    :name "043-channels.sql"
    :statements
    [(add-column! "conversations" "external_conversation_id" "TEXT")
     (add-column! "conversations" "external_endpoint" "TEXT")
     (unique-index! "idx_conversations_external_conversation_id"
                    "ON conversations(external_conversation_id) WHERE external_conversation_id IS NOT NULL")
     (add-column! "messages" "external_message_id" "TEXT")
     (index! "idx_messages_external_message_id"
             "ON messages(conversation_id, external_message_id) WHERE external_message_id IS NOT NULL")]}

   {:number 44
    :name "044-profiles.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS profiles (
         name TEXT PRIMARY KEY,
         is_default BOOLEAN NOT NULL DEFAULT FALSE,
         settings TEXT NOT NULL DEFAULT '{}')   -- JSON: model/reasoning/tools/system prompt"
     (unique-index! "profiles_one_default" "ON profiles(is_default) WHERE is_default")
     "INSERT OR IGNORE INTO profiles (name, is_default) VALUES ('Default', TRUE)"]}

   {:number 45
    :name "045-builtin-profiles.sql"
    :statements
    [(add-column! "profiles" "builtin" "BOOLEAN NOT NULL DEFAULT FALSE")
     (add-column! "profiles" "user_edited" "BOOLEAN NOT NULL DEFAULT TRUE")
     "CREATE TABLE IF NOT EXISTS builtin_profiles (name TEXT PRIMARY KEY)"
     "UPDATE profiles SET builtin = TRUE, user_edited = settings != '{}' WHERE name = 'Default'"
     "INSERT OR IGNORE INTO builtin_profiles (name) VALUES ('Default')"]}

   {:number 46
    :name "046-conversation-settings-edited.sql"
    :statements
    ["UPDATE conversations
         SET conversation_options = json_set(conversation_options, '$.settings_edited', json('true'))
       WHERE json_extract(conversation_options, '$.profile') IS NOT NULL"]}

   {:number 47
    :name "047-live-call-transcripts.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS live_calls (
         conversation_id TEXT NOT NULL REFERENCES conversations(conversation_id) ON DELETE CASCADE,
         call INTEGER NOT NULL,
         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
         PRIMARY KEY (conversation_id, call))"
     "CREATE TABLE IF NOT EXISTS live_call_transcripts (
         conversation_id TEXT NOT NULL,
         call INTEGER NOT NULL,
         n INTEGER NOT NULL,
         user BOOLEAN NOT NULL,
         text TEXT NOT NULL,
         start_ms REAL NOT NULL,
         end_ms REAL NOT NULL,
         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
         PRIMARY KEY (conversation_id, call, n),
         FOREIGN KEY (conversation_id, call) REFERENCES live_calls(conversation_id, call) ON DELETE CASCADE)"]}

   {:number 48
    :name "048-drop-model-tags.sql"
    :statements [(drop-column! "models" "tags")]}

   {:number 49
    :name "049-model-providers.sql"
    :statements
    ["CREATE TABLE IF NOT EXISTS model_providers (
         provider_id TEXT PRIMARY KEY,
         provider_type TEXT NOT NULL,
         endpoint TEXT NOT NULL,
         api_key TEXT NOT NULL,
         display_name TEXT NOT NULL,
         models TEXT NOT NULL,           -- JSON array of model names
         version INTEGER NOT NULL DEFAULT 1)"]}

   ;; --- clj-shelley only ---------------------------------------------------

   {:number 900
    :name "900-add-conversation-title.sql"
    :statements
    [;; Upstream derives a conversation's list label from its first message and
     ;; keeps no column. shelley.server already returns a :title, so the column
     ;; stays; it is ours, hence the number.
     (add-column! "conversations" "title" "TEXT")]}

   {:number 901
    :name "901-iframes.sql"
    :statements
    [;; output_iframe artefacts. Upstream writes these to disk and serves them
     ;; from there; we keep them beside the conversation so a restart still
     ;; serves them.
     "CREATE TABLE IF NOT EXISTS iframes (
         id TEXT PRIMARY KEY,
         conversation_id TEXT,
         title TEXT,
         html TEXT,
         created_at DATETIME)"]}])

(def migration-names
  "Every migration name, in application order. Exposed for tests and for a
  server that wants to report its schema version."
  (mapv :name migrations))

(def upstream-migration-names
  "The names that correspond to a file in the original's db/schema."
  (mapv :name (remove #(>= (:number %) 900) migrations)))

;; ---------------------------------------------------------------------------
;; Names this project used before it mirrored the upstream filenames
;;
;; A database created by the older runner records the names on the left. Every
;; migration is idempotent, so re-running them would be harmless — but it would
;; rewrite `executed_at` with a lie and re-run DDL that is already in place. The
;; runner therefore translates the recorded rows before applying anything: a
;; name with a 1:1 upstream spelling is renamed in place (keeping the original
;; `executed_at`), and a name that was coarser than upstream's history is
;; dropped so the finer-grained upstream files run and find their work already
;; done.
(def legacy-migration-names
  {"001-conversations.sql" "001-conversations.sql"
   "002-messages.sql" "002-messages.sql"
   "003-messages-fts.sql" "021-messages-fts.sql"
   "004-conversation-columns.sql" nil
   "005-message-columns.sql" nil
   "006-custom-models.sql" "012-custom-models.sql"
   "007-model-providers.sql" "049-model-providers.sql"
   "008-settings.sql" "016-settings.sql"
   "009-feature-flags.sql" "024-feature-flags.sql"
   "010-notification-channels.sql" "015-notification-channels.sql"
   "011-cache-sessions.sql" "023-cache-sessions.sql"
   "012-mcp.sql" "041-mcp.sql"
   "013-profiles.sql" "044-profiles.sql"
   "014-background-jobs.sql" "040-add-background-jobs.sql"
   "015-live-calls.sql" "047-live-call-transcripts.sql"
   "016-iframes.sql" "901-iframes.sql"
   "017-conversation-settings-edited.sql" "046-conversation-settings-edited.sql"})

;; ---------------------------------------------------------------------------
;; Runner

(def ^:private migrations-ddl
  ;; The final shape (028-migrations-by-name), created before anything runs so
  ;; the very first migration can be recorded. 001's older DDL is a no-op
  ;; against it.
  "CREATE TABLE IF NOT EXISTS migrations (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     migration_number INTEGER NOT NULL,
     migration_name TEXT NOT NULL UNIQUE,
     executed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)")

(defn applied-migrations
  "Migration names already recorded, in no particular order."
  [ds]
  (when (table-exists? ds "migrations")
    (into #{} (map :migration_name)
          (jdbc/execute! ds ["SELECT migration_name FROM migrations"] rs-opts))))

(defn- apply-one! [ds {:keys [number name statements note]}]
  (jdbc/with-transaction [tx ds]
    (doseq [stmt statements]
      (try
        (if (fn? stmt) (stmt tx) (jdbc/execute! tx [stmt]))
        (catch Exception e
          (throw (ex-info (str "migration " name " failed: " (.getMessage e))
                          {:migration name
                           :statement (if (fn? stmt) :fn stmt)}
                          e)))))
    (jdbc/execute! tx ["INSERT INTO migrations (migration_number, migration_name) VALUES (?, ?)"
                       number name]))
  (if (and note (empty? statements))
    (log/info "migration" name "is a no-op for clj-shelley:" note)
    (log/info "applied migration" name)))

(defn- rewrite-legacy-names!
  "Translate rows recorded under this project's own migration names to the
  upstream names (see `legacy-migration-names`)."
  [ds]
  (let [done (or (applied-migrations ds) #{})
        numbers (into {} (map (juxt :name :number)) migrations)]
    (doseq [[old new] legacy-migration-names
            :when (contains? done old)]
      (cond
        (nil? new)
        (do (jdbc/execute! ds ["DELETE FROM migrations WHERE migration_name = ?" old])
            (log/info "migration" old "split into upstream migrations; re-running them"))

        (contains? done new)
        ;; Already recorded under the upstream name: the old row is a duplicate.
        (jdbc/execute! ds ["DELETE FROM migrations WHERE migration_name = ?" old])

        :else
        (do (jdbc/execute! ds ["UPDATE migrations SET migration_name = ?, migration_number = ?
                                 WHERE migration_name = ?"
                               new (get numbers new) old])
            (log/info "migration" old "renamed to" new))))))

(defn- apply-missing! [ds]
  (let [done (or (applied-migrations ds) #{})]
    (doseq [m (sort-by (juxt :number :name) migrations)
            :when (not (contains? done (:name m)))]
      (apply-one! ds m))))

;; ---------------------------------------------------------------------------
;; Legacy adoption

(def ^:private sqlite-timestamp
  "Legacy rows store epoch milliseconds. Every timestamp column here stores the
  same text SQLite's CURRENT_TIMESTAMP produces, so `ORDER BY updated_at` and
  `strftime` agree across adopted and new rows instead of sorting integers
  against strings."
  (fn [col]
    (str "(CASE WHEN typeof(" col ") IN ('integer','real')"
         " THEN strftime('%Y-%m-%d %H:%M:%S', " col " / 1000.0, 'unixepoch')"
         " ELSE COALESCE(" col ", CURRENT_TIMESTAMP) END)")))

(defn- legacy-messages-table? [tx]
  (and (table-exists? tx "messages") (column-exists? tx "messages" "role")))

(defn- legacy-conversations-table? [tx]
  (and (table-exists? tx "conversations") (column-exists? tx "conversations" "working_dir")))

(defn- copy-conversations! [tx]
  (jdbc/execute! tx
                 [(str "INSERT INTO conversations
                          (conversation_id, slug, title, model, cwd, created_at, updated_at,
                           user_initiated, conversation_options)
                        SELECT id, NULLIF(slug, ''), title, model, working_dir,
                               " (sqlite-timestamp "created_at") ", " (sqlite-timestamp "updated_at") ",
                               1, '{}'
                          FROM conversations_legacy
                         WHERE id IS NOT NULL")]))

(defn- copy-messages! [tx]
  (let [orphans (:n (jdbc/execute-one!
                     tx ["SELECT COUNT(*) AS n FROM messages_legacy
                          WHERE conversation_id NOT IN (SELECT conversation_id FROM conversations)"]
                     rs-opts))]
    (when (pos? (long orphans))
      (log/warn "dropping" orphans "legacy message(s) whose conversation no longer exists"))
    (jdbc/execute! tx
                   [(str "INSERT INTO messages
                            (message_id, conversation_id, sequence_id, type, llm_data, created_at, generation)
                          SELECT lower(hex(randomblob(16))), m.conversation_id,
                                 ROW_NUMBER() OVER (PARTITION BY m.conversation_id ORDER BY m.id),
                                 CASE m.role WHEN 'assistant' THEN 'agent' ELSE m.role END,
                                 m.content, " (sqlite-timestamp "m.created_at") ", 1
                            FROM messages_legacy m
                           WHERE m.conversation_id IN (SELECT conversation_id FROM conversations)
                           ORDER BY m.conversation_id, m.id")])))

(defn- adopt-legacy!
  "Rebuild the pre-migrations clj-shelley tables into the current shape.

  The old schema was `conversations(id, slug, title, model, working_dir,
  created_at, updated_at)` and `messages(id, conversation_id, role, content,
  created_at)`. There is real data in those files, so nothing is dropped: the
  tables are renamed aside, every migration runs to build the current shape,
  the rows are copied (millisecond timestamps converted, `assistant` roles
  renamed `agent`, blank slugs nulled so the unique index holds), and only then
  are the originals dropped. Running all the migrations first is what makes the
  copy legal — the columns it writes are added by 006, 013 and 017."
  [ds]
  (log/info "adopting the pre-migrations clj-shelley schema")
  (jdbc/with-transaction [tx ds]
    (when (legacy-conversations-table? tx)
      (jdbc/execute! tx ["ALTER TABLE conversations RENAME TO conversations_legacy"]))
    (when (legacy-messages-table? tx)
      (jdbc/execute! tx ["ALTER TABLE messages RENAME TO messages_legacy"])))
  (apply-missing! ds)
  (jdbc/with-transaction [tx ds]
    (when (table-exists? tx "conversations_legacy")
      (copy-conversations! tx))
    (when (table-exists? tx "messages_legacy")
      (copy-messages! tx))
    (when (table-exists? tx "conversations_legacy")
      (jdbc/execute! tx ["DROP TABLE conversations_legacy"]))
    (when (table-exists? tx "messages_legacy")
      (jdbc/execute! tx ["DROP TABLE messages_legacy"]))))

(defn adopt-legacy?
  "True when this database still has the pre-migrations clj-shelley shape."
  [ds]
  (or (legacy-conversations-table? ds)
      (legacy-messages-table? ds)))

;; ---------------------------------------------------------------------------
;; Entry point

(defn migrate!
  "Bring `ds` up to date. Safe to call on every start, and cheap after the
  first: it reads the applied names and applies only what is missing."
  [ds]
  (jdbc/execute! ds [migrations-ddl])
  (rewrite-legacy-names! ds)
  (if (adopt-legacy? ds)
    (adopt-legacy! ds)
    (apply-missing! ds))
  (count (applied-migrations ds)))
