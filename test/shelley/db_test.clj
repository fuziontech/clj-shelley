(ns shelley.db-test
  "Tests for the persistence layer.

  Each test gets its own database file, because the schema is now the thing
  under test: the migration runner, adoption of the old schema, and the FTS
  triggers all mutate real SQLite state that later assertions read back."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [shelley.db :as db]
            [shelley.db.migrations :as migrations]
            [shelley.llm :as llm]))

;; ---------------------------------------------------------------------------
;; Harness

(def ^:private db-counter (atom 0))

(defn- temp-db-path []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "clj-shelley-db-test-" (System/currentTimeMillis) "-"
                          (swap! db-counter inc)))]
    (.mkdirs dir)
    (str (io/file dir "shelley.db"))))

(defn- delete-tree! [file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)] (delete-tree! child)))
  (.delete file))

(defn- with-fresh-db [f]
  (let [path (temp-db-path)]
    (binding [db/*db-path* path]
      (db/init!)
      (try (f)
           (finally (delete-tree! (.getParentFile (io/file path))))))))

(use-fixtures :once
  (fn [f]
    (doseq [name ["" "shelley.db" "shelley.db.migrations"]]
      (.setLevel (java.util.logging.Logger/getLogger name) java.util.logging.Level/WARNING))
    (f)))

(use-fixtures :each with-fresh-db)

(defn- table-columns [table]
  (mapv :name (jdbc/execute! (db/ds) [(str "PRAGMA table_info(" table ")")]
                             {:builder-fn rs/as-unqualified-lower-maps})))

(defn- table-names []
  (->> (jdbc/execute! (db/ds) ["SELECT name FROM sqlite_master WHERE type = 'table'"]
                      {:builder-fn rs/as-unqualified-lower-maps})
       (map :name)
       set))

(defn- conversation! [& {:as opts}]
  (db/create-conversation! (merge {:title "a conversation" :model "test/model"} opts)))

(defn- say! [conv-id text & {:as opts}]
  (db/append-message! (merge {:conversation-id conv-id :role :user :content [(llm/text-block text)]} opts)))

(defn- reply! [conv-id text & {:as opts}]
  (db/append-message! (merge {:conversation-id conv-id :role :assistant :content [(llm/text-block text)]} opts)))

;; ---------------------------------------------------------------------------
;; Migrations

(deftest migrations-run-once-and-are-recorded-by-name
  (testing "the first init applies every migration and records it by filename"
    (let [applied (migrations/applied-migrations (db/ds))]
      (is (= (count migrations/migration-names) (count applied)))
      (is (every? applied migrations/migration-names))
      (is (contains? applied "021-messages-fts.sql"))))

  (testing "every migration carries an upstream filename"
    ;; The original's db/schema has 49 files; ours mirror them one for one so
    ;; the two histories read side by side, and the only extra names are the
    ;; ones numbered from 900.
    (is (= 49 (count migrations/upstream-migration-names)))
    (is (= (set migrations/migration-names)
           (into (set migrations/upstream-migration-names)
                 ["900-add-conversation-title.sql" "901-iframes.sql"])))
    (is (every? (set (migrations/applied-migrations (db/ds)))
                migrations/upstream-migration-names)))

  (testing "a second init applies nothing"
    (let [before (migrations/applied-migrations (db/ds))
          rows-before (:n (first (jdbc/execute! (db/ds) ["SELECT COUNT(*) AS n FROM migrations"]
                                                {:builder-fn rs/as-unqualified-lower-maps})))]
      (db/init!)
      (is (= before (migrations/applied-migrations (db/ds))))
      (is (= rows-before (:n (first (jdbc/execute! (db/ds) ["SELECT COUNT(*) AS n FROM migrations"]
                                                   {:builder-fn rs/as-unqualified-lower-maps})))))))

  (testing "a third init keeps existing data"
    (let [conv (conversation!)]
      (say! (:id conv) "still here")
      (db/init!)
      (is (= 1 (count (db/messages (:id conv))))))))

(deftest every-migration-is-idempotent
  ;; The migrations table is not backed up. If it is lost, every file runs again
  ;; against a populated database, so each one must be safe to re-apply: this is
  ;; the property that makes a rename or a renumber harmless.
  (let [conv (conversation!)]
    (say! (:id conv) "replayed migrations keep this")
    (reply! (:id conv) "and this answer too")
    (jdbc/execute! (db/ds) ["DELETE FROM migrations"])

    (db/init!)

    (testing "every migration is recorded again"
      (is (= (set migrations/migration-names) (migrations/applied-migrations (db/ds)))))

    (testing "no data was lost or duplicated"
      (is (= 1 (db/count-conversations)))
      (is (= 2 (count (db/messages (:id conv)))))
      (is (= ["replayed migrations keep this" "and this answer too"]
             (map (comp :text first :content) (db/messages (:id conv))))))

    (testing "the FTS index is neither stale nor double-indexed"
      (is (= 1 (count (db/search-messages "replayed"))))
      (is (= 1 (count (db/search-conversations "replayed")))))))

(deftest migration-names-from-the-old-runner-are-rewritten
  ;; Databases created before the migrations took upstream filenames record
  ;; names like "003-messages-fts.sql". The runner translates them, so those
  ;; files do not run again and `executed_at` keeps its true value.
  (let [conv (conversation!)]
    (say! (:id conv) "written before the rename")
    (jdbc/execute! (db/ds) ["DELETE FROM migrations"])
    (doseq [[n name] [[1 "001-conversations.sql"]
                      [2 "002-messages.sql"]
                      [3 "003-messages-fts.sql"]
                      [4 "004-conversation-columns.sql"]
                      [13 "013-profiles.sql"]
                      [16 "016-iframes.sql"]]]
      (jdbc/execute! (db/ds) ["INSERT INTO migrations (migration_number, migration_name, executed_at)
                               VALUES (?, ?, '2020-01-01 00:00:00')" n name]))

    (db/init!)

    (let [applied (migrations/applied-migrations (db/ds))
          executed (into {}
                         (map (juxt :migration_name :executed_at))
                         (jdbc/execute! (db/ds) ["SELECT migration_name, executed_at FROM migrations"]
                                        {:builder-fn rs/as-unqualified-lower-maps}))]
      (testing "the renamed files are present once, under the upstream names"
        (is (contains? applied "021-messages-fts.sql"))
        (is (contains? applied "044-profiles.sql"))
        (is (contains? applied "901-iframes.sql"))
        (is (not (contains? applied "003-messages-fts.sql")))
        (is (not (contains? applied "016-iframes.sql")))
        (is (= (count migrations/migration-names) (count applied))))
      (testing "a 1:1 rename keeps the original executed_at"
        (is (= "2020-01-01 00:00:00" (get executed "021-messages-fts.sql")))
        (is (= "2020-01-01 00:00:00" (get executed "044-profiles.sql"))))
      (testing "the data is untouched"
        (is (= 1 (count (db/messages (:id conv)))))
        (is (str/includes? (-> (db/messages (:id conv)) first :content first :text)
                           "before the rename"))
        (is (= 1 (count (db/search-conversations "written before"))))))))

(deftest schema-has-the-columns-parity-needs
  (let [conv-cols (set (table-columns "conversations"))
        msg-cols (set (table-columns "messages"))
        tables (table-names)]
    (testing "conversations"
      (is (every? conv-cols (map name
                  [:conversation_id :slug :user_initiated :created_at :updated_at :title :cwd
                   :archived :parent_conversation_id :model :conversation_options
                   :current_generation :agent_working :tags :is_draft :draft :queued_messages
                   :turn_interrupted :external_conversation_id :external_endpoint]))))
    (testing "messages"
      (is (every? msg-cols (map name
                  [:message_id :conversation_id :sequence_id :type :llm_data :user_data
                   :usage_data :display_data :excluded_from_context :generation :llm_api_url
                   :model_name :forked_from_message_id :user_email :other_usage_data
                   :external_message_id :created_at]))))
    (testing "every supporting table exists"
      (is (every? tables
                  ["conversations" "messages" "messages_fts" "migrations" "models"
                   "model_providers" "settings" "feature_flags" "notification_channels"
                   "cache_sessions" "mcp_servers" "mcp_oauth" "profiles" "builtin_profiles"
                   "background_jobs" "live_calls" "live_call_transcripts" "iframes"])))
    (testing "the slug unique index only covers non-null slugs, so many rows can be nameless"
      (conversation!) (conversation!)
      (is (= 2 (count (db/list-conversations)))))
    (testing "the default profile is seeded"
      (is (= ["Default"] (map :name (db/list-profiles))))
      (is (:is-default (db/default-profile))))))

(deftest adopting-a-legacy-database-keeps-its-rows
  (let [path (temp-db-path)]
    ;; Build the pre-migrations clj-shelley database by hand.
    (let [raw (jdbc/get-datasource {:dbtype "sqlite" :dbname path})]
      (jdbc/execute! raw ["CREATE TABLE conversations (
          id TEXT PRIMARY KEY, slug TEXT, title TEXT, model TEXT, working_dir TEXT,
          created_at INTEGER, updated_at INTEGER)"])
      (jdbc/execute! raw ["CREATE TABLE messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL, role TEXT NOT NULL,
          content TEXT NOT NULL, created_at INTEGER NOT NULL)"])
      (jdbc/execute! raw ["INSERT INTO conversations VALUES (?,?,?,?,?,?,?)"
                          "old-1" "" "older" "anthropic/x" "/tmp/old" 1700000000000 1700000000000])
      (jdbc/execute! raw ["INSERT INTO conversations VALUES (?,?,?,?,?,?,?)"
                          "old-2" "named-one" "newer" "anthropic/y" "/tmp/new" 1700000500000 1700000600000])
      (jdbc/execute! raw ["INSERT INTO messages (conversation_id, role, content, created_at)
                           VALUES (?,?,?,?)"
                          "old-1" "user" (json/generate-string [(llm/text-block "a pelican sighting")]) 1700000000000])
      (jdbc/execute! raw ["INSERT INTO messages (conversation_id, role, content, created_at)
                           VALUES (?,?,?,?)"
                          "old-1" "assistant"
                          (json/generate-string [{:type :tool-use :id "t1" :name "bash" :input {:command "ls"}}
                                                 {:type :tool-result :tool-use-id "t1" :is-error true
                                                  :content [{:type :text :text "boom"}]}])
                          1700000001000])
      (jdbc/execute! raw ["INSERT INTO messages (conversation_id, role, content, created_at)
                           VALUES (?,?,?,?)"
                          "old-2" "user" (json/generate-string [(llm/text-block "hello")]) 1700000500000]))

    (binding [db/*db-path* path]
      (db/init!)

      (testing "every conversation survived with its identity"
        (is (= 2 (db/count-conversations)))
        (is (= "named-one" (:slug (db/get-conversation "old-2"))))
        (is (nil? (:slug (db/get-conversation "old-1"))) "blank slugs are nulled so the unique index holds")
        (is (= "older" (:title (db/get-conversation "old-1"))))
        (is (= "/tmp/new" (:cwd (db/get-conversation "old-2"))))
        (is (= "/tmp/new" (:working_dir (db/get-conversation "old-2"))))
        (is (= "anthropic/y" (:model (db/get-conversation "old-2"))))
        (is (:user-initiated (db/get-conversation "old-1"))))

      (testing "millisecond timestamps became SQLite datetimes, in the same order"
        (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" (:updated_at (db/get-conversation "old-2"))))
        (is (= ["old-2" "old-1"] (mapv :id (db/list-conversations)))))

      (testing "messages kept their blocks, types and order"
        (let [msgs (db/messages "old-1")]
          (is (= 2 (count msgs)))
          (is (= [:user :assistant] (mapv :role msgs)))
          (is (= [1 2] (mapv :sequence-id msgs)))
          (is (= "a pelican sighting" (-> msgs first :content first :text)))
          (is (= :tool-use (-> msgs second :content first :type)))
          (is (= {:command "ls"} (-> msgs second :content first :input)))
          (is (= :tool-result (-> msgs second :content second :type)))
          (is (= "t1" (-> msgs second :content second :tool-use-id)))
          (is (true? (-> msgs second :content second :is-error)))
          (is (= :text (-> msgs second :content second :content first :type)))))

      (testing "the search index was built over the adopted rows"
        (let [hits (db/search-conversations "pelican")]
          (is (= ["old-1"] (mapv :id hits)))
          (is (str/includes? (:snippet (first hits)) "pelican"))))

      (testing "the legacy tables are gone"
        (is (not (contains? (table-names) "conversations_legacy")))
        (is (not (contains? (table-names) "messages_legacy")))))))

(deftest an-adopted-database-is-then-ordinary
  (testing "a fresh insert after adoption gets the next sequence id"
    ;; Adoption is covered above; this pins that the copy left no sequence gaps
    ;; that the new insert path would collide with.
    (let [conv (conversation!)]
      (say! (:id conv) "one")
      (say! (:id conv) "two")
      (is (= [1 2] (mapv :sequence-id (db/messages (:id conv))))))))

;; ---------------------------------------------------------------------------
;; Messages

(deftest blocks-survive-the-round-trip
  (let [conv (conversation!)
        blocks [{:type :thinking :thinking "hmm" :signature "sig"}
                {:type :text :text "here you go"}
                {:type :tool-use :id "t1" :name "bash" :input {:command "ls -la"}}
                {:type :tool-use :id "t2" :name "read_image" :input {:path "x.png"}}
                {:type :image :media-type "image/png" :data "aGk="}]]
    (db/append-message! {:conversation-id (:id conv) :type :agent :content blocks
                         :usage-data {:input-tokens 10 :cache-read-tokens 2}
                         :model-name "claude-x" :llm-api-url "https://api.anthropic.com/v1/messages"})
    (let [msg (first (db/messages (:id conv)))]
      (testing "every block keeps its :type keyword"
        (is (= [:thinking :text :tool-use :tool-use :image] (mapv :type (:content msg)))))
      (testing "block fields keep their kebab-case keys"
        (is (= "sig" (-> msg :content first :signature)))
        (is (= "t1" (-> msg :content (nth 2) :id)))
        (is (= {:command "ls -la"} (-> msg :content (nth 2) :input)))
        (is (= "image/png" (-> msg :content last :media-type))))
      (testing "the row's JSON columns decode to kebab-case keys"
        (is (= {:input-tokens 10 :cache-read-tokens 2} (:usage-data msg)))
        (is (= "claude-x" (:model-name msg)))
        (is (= "https://api.anthropic.com/v1/messages" (:llm-api-url msg)))))

  (testing "a nested tool result round-trips through normalize-block"
    (let [conv (conversation!)]
      (db/append-message! {:conversation-id (:id conv) :role :user
                           :content [{:type :tool-result :tool-use-id "t9" :is-error false
                                      :content [{:type :text :text "ok"}
                                                {:type :image :media-type "image/png" :data "aGk="}]}]})
      (let [block (-> (db/messages (:id conv)) first :content first)]
        (is (= :tool-result (:type block)))
        (is (= "t9" (:tool-use-id block)))
        (is (false? (:is-error block)))
        (is (= [:text :image] (mapv :type (:content block)))))))

  (testing "a plain string is accepted as content"
    (let [conv (conversation!)]
      (db/append-message! {:conversation-id (:id conv) :role :user :content "hi"})
      (is (= [{:type :text :text "hi"}] (-> (db/messages (:id conv)) first :content)))))))

(deftest message-types-and-roles
  (let [conv (conversation!)]
    (say! (:id conv) "from the user")
    (reply! (:id conv) "from the agent")
    (db/append-message! {:conversation-id (:id conv) :type :error :content [(llm/text-block "boom")]})
    (db/append-message! {:conversation-id (:id conv) :type :gitinfo :content [(llm/text-block "sha")]})

    (testing "the model's context skips rows the model must never see"
      (is (= [:user :agent] (mapv :type (db/messages (:id conv))))))

    (testing "the transcript keeps everything"
      (is (= [:user :agent :error :gitinfo] (mapv :type (db/transcript (:id conv)))))
      (is (= [:error] (mapv :type (db/transcript (:id conv) {:types [:error]})))))

    (testing "counts and by-type reads"
      (is (= 4 (db/count-messages (:id conv))))
      (is (= 1 (db/count-messages (:id conv) :error)))
      (is (= [:gitinfo] (mapv :type (db/messages-by-type (:id conv) :gitinfo)))))

    (testing "the latest actionable message ignores invisible bookkeeping"
      (db/append-message! {:conversation-id (:id conv) :type :slug :other-usage [{:purpose "slug"}]})
      (is (= :gitinfo (:type (db/latest-actionable-message (:id conv))))))

    (testing "rows excluded from context are stored but not sent"
      (db/append-message! {:conversation-id (:id conv) :type :agent
                           :content [(llm/text-block "truncated")]
                           :excluded-from-context true})
      (is (= 6 (count (db/transcript (:id conv)))))
      (is (= 1 (count (filter #(= :agent (:type %)) (db/messages (:id conv)))))))))

(deftest sequence-ordering
  (let [conv (conversation!) other (conversation!)]
    (db/append-messages! [{:conversation-id (:id conv) :role :user :content [(llm/text-block "1")]}
                          {:conversation-id (:id conv) :role :assistant :content [(llm/text-block "2")]}
                          {:conversation-id (:id conv) :role :user :content [(llm/text-block "3")]}])
    (say! (:id conv) "4")
    (say! (:id other) "other conversation")

    (testing "sequence ids are per conversation and contiguous"
      (is (= [1 2 3 4] (mapv :sequence-id (db/messages (:id conv)))))
      (is (= [1] (mapv :sequence-id (db/messages (:id other))))))

    (testing "the tail returns the newest rows in ascending order"
      (is (= [3 4] (mapv :sequence-id (db/messages-tail (:id conv) 2)))))

    (testing "since is the client cache-repair path"
      (is (= [3 4] (mapv :sequence-id (db/messages-since (:id conv) 2)))))

    (testing "paging a transcript"
      (is (= [3 4] (mapv :sequence-id (db/transcript (:id conv) {:limit 2 :offset 2}))))
      (is (= [3 4] (mapv :sequence-id (db/transcript (:id conv) {:before 5 :limit 2})))))))

(deftest appending-bumps-the-conversation
  (let [conv (conversation!)]
    (let [before (:updated-at (db/get-conversation (:id conv)))]
      (Thread/sleep 1100) ; CURRENT_TIMESTAMP has second resolution
      (say! (:id conv) "tick")
      (is (not= before (:updated-at (db/get-conversation (:id conv))))))
    (testing "an append can skip the bump"
      (let [before (:updated-at (db/get-conversation (:id conv)))]
        (Thread/sleep 1100)
        (say! (:id conv) "no tick" :bump-timestamp false)
        (is (= before (:updated-at (db/get-conversation (:id conv)))))))
  (testing "appending to an unknown conversation is an error, not a silent orphan"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such conversation"
                          (db/append-message! {:conversation-id "nope" :role :user
                                               :content [(llm/text-block "x")]}))))))

;; ---------------------------------------------------------------------------
;; Search

(deftest full-text-search-finds-the-right-conversation
  (let [slug-hit (conversation! :slug "pelican-notes")
        body-hit (conversation! :slug "unrelated")
        decoy (conversation! :slug "decoy")
        archived (conversation! :slug "pelican-archived")]
    (reply! (:id body-hit) "a pelican stood by the bay")
    (say! (:id decoy) "nothing to see here")
    (reply! (:id archived) "another pelican")
    (db/archive-conversation! (:id archived))

    (testing "slug matches come first, then message matches, newest first"
      (let [hits (db/search-conversations "pelican")]
        (is (= [(:id slug-hit) (:id body-hit)] (mapv :id hits)))
        (is (= [0 1] (mapv :slug-rank hits)))
        (is (= "" (:snippet (first hits))) "a slug-only match has no snippet")
        (is (str/includes? (:snippet (second hits)) "pelican"))
        (is (str/includes? (:snippet (second hits)) db/snippet-mark-start))
        (is (= (-> (db/messages (:id body-hit)) first :message-id)
               (:snippet-message-id (second hits))))))

    (testing "archived conversations are searched separately"
      (is (= [(:id archived)] (mapv :id (db/search-conversations "pelican" {:archived true})))))

    (testing "a term with no hits returns nothing"
      (is (= [] (db/search-conversations "wombat"))))

    (testing "paging"
      (is (= [(:id body-hit)] (mapv :id (db/search-conversations "pelican" {:limit 1 :offset 1}))))
      (is (= [] (db/search-conversations "pelican" {:limit 1 :offset 5}))))

    (testing "per-message search returns the matched row"
      (let [hits (db/search-messages "pelican")]
        (is (contains? (set (map :conversation-id hits)) (:id body-hit)))
        (is (every? #(str/includes? (:snippet %) "pelican") hits))
        (is (contains? (set (map :role hits)) :assistant))))

    (testing "the slug substring search is the non-FTS path"
      (is (= [(:id slug-hit)] (mapv :id (db/list-conversations {:q "pelican"})))))

    (testing "query input cannot be read as an FTS operator"
      (is (= [] (db/search-conversations "AND OR NOT")))
      (is (= "\"AND\"* AND \"OR\" AND \"NOT\"*" (db/fts-match "AND OR NOT")))
      (is (= "\"a\" AND \"pelican\"*" (db/fts-match "a pelican"))))

    (testing "short terms only get a prefix when they are long enough"
      (let [c (conversation!)]
        (reply! (:id c) "use jq and R")
        (is (= [(:id c)] (mapv :conversation-id (db/search-messages "jq"))))
        (is (= [(:id c)] (mapv :conversation-id (db/search-messages "R"))))
        (is (= [] (mapv :conversation-id (db/search-messages "q")))
            "a one-rune term would match everything, so it is not a prefix")))

    (testing "an empty query searches nothing and breaks nothing"
      (is (= [] (db/search-conversations "   ")))
      (is (= [] (db/search-messages ""))))))

(deftest search-follows-the-message-row-through-updates
  (let [conv (conversation!)]
    ;; No llm_data, so user_data is what the index reads.
    (db/append-message! {:conversation-id (:id conv) :type :agent :user-data {:text "before the edit"}})
    (let [msg (first (db/messages (:id conv)))]
      (is (= [] (db/search-messages "cormorant")))
      (testing "the AFTER UPDATE trigger re-indexes the row"
        (db/update-message-user-data! (:message-id msg) {:text "a cormorant appears"})
        (is (= [(:id conv)] (mapv :conversation-id (db/search-messages "cormorant")))))
      (testing "deleting a conversation takes its rows out of the index"
        (db/delete-conversation! (:id conv))
        (is (= [] (db/search-messages "cormorant")))))))

(deftest search-indexes-ui-only-text-when-there-is-no-llm-data
  (let [conv (conversation!)]
    (db/append-message! {:conversation-id (:id conv) :type :agent :user-data {:text "a shag in the water"}})
    (is (= [(:id conv)] (mapv :conversation-id (db/search-messages "shag"))))))

;; ---------------------------------------------------------------------------
;; Archiving, tags, drafts

(deftest archive-and-tag-filtering
  (let [a (conversation!) b (conversation!) c (conversation!)]
    (db/set-tags! (:id a) ["research" "birds"])
    (db/add-tag! (:id b) "research")
    (db/add-tag! (:id b) "research")
    (db/add-tag! (:id c) "other")

    (testing "tags round-trip, deduplicated"
      (is (= ["research" "birds"] (db/tags (:id a))))
      (is (= ["research"] (db/tags (:id b))))
      (is (= ["research" "birds"] (:tags (db/get-conversation (:id a))))))

    (testing "list-tagged selects by tag"
      (is (= #{(:id a) (:id b)} (set (map :id (db/list-tagged "research")))))
      (is (= [(:id c)] (mapv :id (db/list-tagged "other")))))

    (testing "remove-tag"
      (db/remove-tag! (:id a) "birds")
      (is (= ["research"] (db/tags (:id a)))))

    (testing "tagging does not reorder the conversation list"
      (let [before (:updated-at (db/get-conversation (:id c)))]
        (Thread/sleep 1100)
        (db/add-tag! (:id c) "late")
        (is (= before (:updated-at (db/get-conversation (:id c)))))))

    (testing "archiving hides a conversation from the default list"
      (db/set-archived! (:id b) true)
      (is (not (contains? (set (map :id (db/list-conversations))) (:id b))))
      (is (= [(:id b)] (mapv :id (db/list-conversations {:archived true}))))
      (is (true? (:archived (db/get-conversation (:id b)))))
      (db/unarchive-conversation! (:id b))
      (is (contains? (set (map :id (db/list-conversations))) (:id b))))

    (testing "list-tagged can include archived conversations"
      (db/archive-conversation! (:id b))
      (is (= [(:id b)] (mapv :id (db/list-tagged "research" {:archived true})))))))

(deftest drafts
  (let [draft (db/create-draft-conversation! {:draft "half a thought" :model "m"})]
    (testing "a draft lists like any conversation, carrying its body"
      (is (true? (:is-draft draft)))
      (is (= "half a thought" (:draft draft)))
      (is (= [(:id draft)] (mapv :id (db/list-conversations)))))

    (testing "a draft is edited in place, key by key"
      (let [updated (db/update-draft! (:id draft) {:draft "a whole thought"})]
        (is (= "a whole thought" (:draft updated)))
        (is (= "m" (:model updated)))))

    (testing "promoting clears draft state"
      (let [promoted (db/promote-draft! (:id draft))]
        (is (false? (:is-draft promoted)))
        (is (= "" (:draft promoted)))
        (is (nil? (db/promote-draft! (:id draft))) "promoting twice is a no-op")))

    (testing "a promoted conversation is no longer editable as a draft"
      (is (nil? (db/update-draft! (:id draft) {:draft "too late"}))))

    (testing "promotion drops draft-only options"
      (let [d (db/create-draft-conversation! {:draft "x" :options {:kind "search"}})]
        (is (= "search" (:kind (:options (db/get-conversation (:id d))))))
        (is (nil? (:kind (:options (db/promote-draft! (:id d))))))))))

;; ---------------------------------------------------------------------------
;; Queued messages

(deftest queued-messages
  (let [conv (conversation!)
        q1 (db/append-queued-message! (:id conv) {:llm {:role :user :content [(llm/text-block "queued one")]}
                                                  :model "m"})
        q2 (db/append-queued-message! (:id conv) {:llm {:role :user :content [(llm/text-block "queued two")]}
                                                  :model "m" :inject true})]
    (testing "queued messages live on the conversation, not in messages"
      (is (= [] (db/messages (:id conv))))
      (is (= 2 (count (db/queued-messages (:id conv)))))
      (is (= 2 (count (:queued-messages (db/get-conversation (:id conv)))))))

    (testing "the stored message is still a message"
      (let [entry (first (db/queued-messages (:id conv)))]
        (is (string? (:id entry)))
        (is (= :user (-> entry :llm :role)))
        (is (= :text (-> entry :llm :content first :type)))
        (is (= "m" (:model entry)))
        (is (string? (:created-at entry)))))

    (testing "individual entries can be read and removed"
      (is (= (:id (first (db/queued-messages (:id conv)))) (:id (db/get-queued-message (:id conv) (:id (first (db/queued-messages (:id conv))))))))
      (let [qid (:id (first (db/queued-messages (:id conv))))]
        (db/remove-queued-messages! (:id conv) [qid])
        (is (= 1 (count (db/queued-messages (:id conv)))))))

    (testing "draining removes the entry in the same transaction as the insert"
      (let [qid (:id (first (db/queued-messages (:id conv))))
            msg (db/append-message! {:conversation-id (:id conv) :role :user
                                     :content (-> (db/get-queued-message (:id conv) qid) :llm :content)
                                     :remove-queued-id qid})]
        (is (some? (:message-id msg)))
        (is (= [] (db/queued-messages (:id conv))))
        (is (= 1 (count (db/messages (:id conv)))))))

    (testing "draining an absent entry is an error rather than a silent drop"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"queued message not found"
                            (db/append-message! {:conversation-id (:id conv) :role :user
                                                 :content [(llm/text-block "x")]
                                                 :remove-queued-id "nope"}))))

    (testing "the queue can be cleared"
      (db/append-queued-message! (:id conv) {:llm {:role :user} :model "m"})
      (db/clear-queued-messages! (:id conv))
      (is (= [] (db/queued-messages (:id conv)))))))

(deftest corrupt-queued-messages-are-not-overwritten
  (let [conv (conversation!)]
    (jdbc/execute! (db/ds) ["UPDATE conversations SET queued_messages = ? WHERE conversation_id = ?"
                            "{not json" (:id conv)])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"corrupt queued_messages"
                          (db/queued-messages (:id conv))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (db/append-queued-message! (:id conv) {:llm {:role :user}})))))

;; ---------------------------------------------------------------------------
;; Generations, options, runtime state, subagents

(deftest generations-and-options
  (let [conv (conversation!)]
    (testing "options start empty and are written as the original spells them"
      (is (= {} (db/conversation-options (:id conv))))
      (is (= "high" (:thinking-level (db/set-thinking-level! (:id conv) "high"))))
      (is (= "high" (:thinking_level (json/parse-string
                                       (:conversation_options (jdbc/execute-one!
                                                               (db/ds) ["SELECT conversation_options FROM conversations"]
                                                               {:builder-fn rs/as-unqualified-lower-maps}))
                                       true)))))

    (testing "setting one option keeps the others"
      (db/set-conversation-option! (:id conv) :kind "btw")
      (is (= {:thinking-level "high" :kind "btw"} (db/conversation-options (:id conv)))))

    (testing "modify reports whether it changed anything"
      (let [[opts changed] (db/modify-conversation-options! (:id conv) #(assoc % :system-prompt "be brief"))]
        (is (true? changed))
        (is (= "be brief" (:system-prompt opts))))
      (let [[_ changed] (db/modify-conversation-options! (:id conv) identity)]
        (is (false? changed))))

    (testing "settings-edited is recorded in the options JSON"
      (db/mark-settings-edited! (:id conv))
      (is (true? (:settings-edited (db/conversation-options (:id conv))))))

    (testing "generations increment and roll back"
      (is (= 1 (:current-generation (db/get-conversation (:id conv)))))
      (is (= 2 (:current-generation (db/increment-generation! (:id conv)))))
      (is (= 1 (:current-generation (db/set-generation! (:id conv) 1)))))

    (testing "a new generation is what the model sees"
      (say! (:id conv) "old generation")
      (db/increment-generation! (:id conv))
      (say! (:id conv) "new generation")
      (is (= ["new generation"] (mapv #(-> % :content first :text) (db/messages (:id conv)))))
      (is (= 2 (count (db/transcript (:id conv))))))

    (testing "older generations can still be requested explicitly"
      (is (= ["old generation"] (mapv #(-> % :content first :text)
                                       (db/messages (:id conv) {:generation 1})))))))

(deftest agent-working-and-interruption
  (let [conv (conversation!)]
    (db/set-turn-interrupted! (:id conv) true)
    (testing "working state does not reorder the list"
      (let [before (:updated-at (db/get-conversation (:id conv)))]
        (Thread/sleep 1100)
        (db/set-agent-working! (:id conv) true)
        (is (= before (:updated-at (db/get-conversation (:id conv)))))
        (is (true? (:agent-working (db/get-conversation (:id conv)))))))
    (testing "starting a turn clears the interruption"
      (is (false? (:turn-interrupted (db/get-conversation (:id conv))))))
    (testing "an interrupted, idle conversation can be claimed exactly once"
      (db/set-agent-working! (:id conv) false)
      (db/set-turn-interrupted! (:id conv) true)
      (is (true? (db/claim-interrupted-turn! (:id conv))))
      (is (false? (db/claim-interrupted-turn! (:id conv)))))
    (testing "reset clears stale working flags on startup"
      (db/set-agent-working! (:id conv) true)
      (is (= [(:id conv)] (db/list-agent-working-conversation-ids)))
      (db/reset-all-agent-working!)
      (is (= [] (db/list-agent-working-conversation-ids))))
    (testing "a subagent conversation cannot be claimed for manual resume"
      (let [parent (conversation!)
            child (db/create-subagent-conversation! {:parent-id (:id parent) :slug "sub"})]
        (is (false? (:user-initiated child)))
        (is (= (:id parent) (:parent-id child)))
        (db/set-turn-interrupted! (:id child) true)
        (is (false? (db/claim-interrupted-turn! (:id child))))))))

(deftest subagents-are-separate-from-the-default-list
  (let [parent (conversation!)
        child (db/create-subagent-conversation! {:parent-id (:id parent) :slug "child" :cwd "/tmp"})]
    (is (= [(:id child)] (mapv :id (db/subagents (:id parent)))))
    (is (not (contains? (set (map :id (db/list-conversations))) (:id child))))
    (is (contains? (set (map :id (db/list-conversations {:include-subagents true}))) (:id child)))
    (testing "the parent's derived count includes it"
      (is (= 1 (:subagent-count (first (filter #(= (:id parent) (:id %))
                                               (db/list-conversations)))))))))

;; ---------------------------------------------------------------------------
;; Participants, forks, slugs

(deftest participants-and-emails
  (let [conv (conversation!)]
    (say! (:id conv) "from alice" :user-email "alice@example.com")
    (reply! (:id conv) "reply")
    (say! (:id conv) "from bob" :user-email "bob@example.com")
    (say! (:id conv) "from alice again" :user-email "alice@example.com")
    (is (= [{:email "alice@example.com" :message-count 2}
            {:email "bob@example.com" :message-count 1}]
           (sort-by :email (get (db/participants [(:id conv)]) (:id conv)))))
    (testing "the list item carries participants"
      (let [item (first (filter #(= (:id conv) (:id %)) (db/list-conversations)))]
        (is (= 2 (count (:participants item))))))
    (testing "a message remembers its author"
      (is (= "alice@example.com" (:user-email (first (db/messages (:id conv)))))))))

(deftest forking-records-provenance
  (let [source (conversation! :slug "source" :model "m1")]
    (say! (:id source) "one")
    (reply! (:id source) "two" :usage-data {:input-tokens 5 :output-tokens 7})
    (say! (:id source) "three")
    (let [fork (db/fork-conversation! {:source-id (:id source) :cutoff-sequence-id 2})]
      (testing "the fork copies up to the cutoff, in order"
        (is (not= (:id source) (:id fork)))
        (is (= [1 2] (mapv :sequence-id (db/messages (:id fork)))))
        (is (= ["one" "two"] (mapv #(-> % :content first :text) (db/messages (:id fork)))))
        (is (= "m1" (:model fork))))
      (testing "copies point back at their originals"
        (let [original-ids (mapv :message-id (db/messages (:id source)))
              copied (db/messages (:id fork))]
          (is (= (subvec original-ids 0 2) (mapv :forked-from copied)))
          (is (every? #(not (contains? (set original-ids) (:message-id %))) copied))))
      (testing "the fork is independent of the source"
        (db/delete-conversation! (:id source))
        (is (= 2 (count (db/messages (:id fork)))))))))

(deftest slugs
  (testing "slugs are unique when present"
    (conversation! :slug "taken")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already taken"
                          (conversation! :slug "taken"))))

  (let [conv (conversation!)
        id (:id conv)]
    (testing "a slug can be installed while unnamed"
      (let [[slugged updated] (db/set-slug-if-unset! id "first-guess")]
        (is (true? updated))
        (is (= "first-guess" (:slug slugged)))))
    (testing "a second guess cannot overwrite it"
      (let [[slugged updated] (db/set-slug-if-unset! id "second-guess")]
        (is (false? updated))
        (is (= "first-guess" (:slug slugged)))))
    (testing "lookup by slug"
      (is (= id (:id (db/get-conversation-by-slug "first-guess")))))
    (testing "slug candidates walk from the base to a random suffix"
      (is (= ["base" "base-1" "base-2" "base-3"]
             (mapv #(db/slug-candidate "base" %) [0 1 2 3])))
      (is (str/starts-with? (db/slug-candidate "base" 4) "base-")))))

;; ---------------------------------------------------------------------------
;; Supporting tables

(deftest settings-and-feature-flags
  (testing "settings"
    (is (nil? (db/get-setting :model-name)))
    (db/set-setting! :model-name "gpt")
    (is (= "gpt" (db/get-setting "model-name")))
    (db/set-setting! :model-name "other")
    (is (= "other" (db/get-setting :model-name)))
    (is (= "other" (:model-name (db/all-settings))))
    (is (= "kept" (db/init-setting! :kept "kept")))
    (is (= "kept" (db/init-setting! :kept "ignored")))
    (is (= 1 (db/delete-setting! :model-name)))
    (is (nil? (db/get-setting :model-name))))

  (testing "feature flags store JSON values"
    (db/set-feature-flag! :new-ui true)
    (db/set-feature-flag! :rollout 25)
    (db/set-feature-flag! :mode "shadow")
    (is (= {:new-ui true :rollout 25 :mode "shadow"} (db/feature-flags)))
    (is (= 1 (db/delete-feature-flag! :new-ui)))
    (is (= {:rollout 25 :mode "shadow"} (db/feature-flags)))))

(deftest custom-models-and-providers
  (let [model (db/save-model! {:model-id "m1" :display-name "Mine" :provider-type "anthropic"
                               :endpoint "https://example.test" :api-key "k"
                               :model-name "claude-x" :max-tokens 1000})]
    (is (= "Mine" (:display-name model)))
    (is (= 1000 (:max-tokens model)))
    (is (= "auto" (:image-support model)))
    (is (= ["m1"] (mapv :model-id (db/list-models))))
    (testing "saving again updates rather than duplicating"
      (db/save-model! {:model-id "m1" :display-name "Renamed" :provider-type "openai"
                       :endpoint "https://example.test" :api-key "k2" :model-name "gpt-x"})
      (is (= 1 (count (db/list-models))))
      (is (= "Renamed" (:display-name (db/get-model "m1"))))
      (is (= 200000 (:max-tokens (db/get-model "m1")))))
    (is (= 1 (db/delete-model! "m1"))))

  (let [provider (db/save-model-provider! {:provider-id "p1" :provider-type "openai"
                                           :endpoint "https://example.test" :api-key "k"
                                           :display-name "Provider" :models ["m1" "m2"]})]
    (is (= ["m1" "m2"] (:models provider)))
    (is (= 1 (:version provider)))
    (is (= ["p1"] (mapv :provider-id (db/list-model-providers))))
    (is (= 1 (db/delete-model-provider! "p1")))))

(deftest profiles
  (testing "the seeded default is the only default"
    (is (= "Default" (:name (db/default-profile))))
    (is (true? (:builtin (db/default-profile))))
    (is (false? (:user-edited (db/default-profile)))))

  (db/create-profile! "Fast" {:model "m" :thinking-level "off"})
  (is (= {:model "m" :thinking-level "off"} (:settings (db/get-profile "Fast"))))
  (testing "creating an existing profile does not overwrite it"
    (db/create-profile! "Fast" {:model "other"})
    (is (= "m" (:model (:settings (db/get-profile "Fast"))))))
  (testing "editing a profile marks it user-edited"
    (is (true? (:user-edited (db/update-profile-settings! "Fast" {:model "m2"})))))
  (testing "exactly one profile is the default at a time"
    (db/set-default-profile! "Fast")
    (is (= "Fast" (:name (db/default-profile))))
    (is (= ["Fast" "Default"] (mapv :name (db/list-profiles))))
    (db/set-default-profile! "Default"))
  (testing "the default profile cannot be deleted"
    (is (= 0 (db/delete-profile! "Default"))))
  (testing "other profiles can"
    (is (= 1 (db/delete-profile! "Fast"))))
  (testing "built-ins are registered separately so deleting one does not reinstall it"
    (db/register-builtin-profile! "Helper")
    (is (db/builtin-profile? "Helper"))
    (db/create-builtin-profile! "Helper" {:model "m"})
    (is (true? (:builtin (db/get-profile "Helper"))))
    (is (= ["Default" "Helper"] (db/builtin-profiles)))
    (is (false? (:user-edited (db/restore-builtin-profile! "Helper" {:model "reset"}))))))

(deftest notification-channels
  (let [c (db/save-notification-channel! {:channel-type "discord" :display-name "Ops"
                                          :config {:webhook "https://example.test/hook"}})]
    (is (true? (:enabled c)))
    (is (= "https://example.test/hook" (-> c :config :webhook)))
    (is (= 1 (count (db/list-notification-channels))))
    (db/save-notification-channel! (assoc c :enabled false :display-name "Ops (off)"))
    (is (= 1 (count (db/list-notification-channels))))
    (is (= [] (db/list-notification-channels {:enabled-only? true})))
    (is (= 1 (db/delete-notification-channel! (:channel-id c))))))

(deftest cache-sessions
  (db/upsert-cache-session! "hash-1" "alice@example.com")
  (is (= "alice@example.com" (:user_id (db/get-cache-session "hash-1"))))
  (db/upsert-cache-session! "hash-1" "bob@example.com")
  (is (= "bob@example.com" (:user_id (db/get-cache-session "hash-1"))))
  (is (= 1 (db/delete-cache-session! "hash-1")))
  (is (nil? (db/get-cache-session "hash-1"))))

(deftest mcp-servers
  (let [server (db/save-mcp-server! {:name "linear" :url "https://mcp.example.test"
                                     :description "issues" :headers {:authorization "Bearer x"}})]
    (is (= "issues" (:description server)))
    (is (= "Bearer x" (-> server :headers :authorization)))
    (is (= ["linear"] (mapv :name (db/list-mcp-servers))))
    (db/save-mcp-oauth! "linear" {:client-id "cid" :auth-url "https://auth"}
                        {:access-token "tok" :expiry "2026-01-01T00:00:00Z"})
    (let [oauth (db/get-mcp-oauth "linear")]
      (is (= "cid" (-> oauth :client :client-id)))
      (is (= "tok" (-> oauth :token :access-token))))
    (is (= 1 (db/delete-mcp-oauth! "linear")))
    (is (= 1 (db/delete-mcp-server! "linear")))))

(deftest background-jobs
  (let [conv (conversation!)
        job (db/save-background-job! {:conversation-id (:id conv) :tool-use-id "t1"
                                      :command "sleep 100" :pid 1234 :process-start-time 99
                                      :log-path "/tmp/log" :exit-path "/tmp/exit"
                                      :started-at "2026-01-01 00:00:00"})]
    (is (string? (:job-id job)))
    (is (false? (:exited job)))
    (is (= 1 (count (db/list-running-background-jobs (:id conv)))))
    (is (= 1 (count (db/list-unfinished-background-jobs))))
    (testing "the conversation list counts running jobs"
      (is (= 1 (:running-background-jobs (first (db/list-conversations))))))
    (testing "marking a job exited reports whether it had been notified"
      (is (false? (db/mark-background-job-exited! (:job-id job))))
      (is (nil? (db/mark-background-job-exited! (:job-id job))))
      (is (= [] (db/list-running-background-jobs (:id conv))))
      (is (= 1 (count (db/list-unfinished-background-jobs))) "exited but not yet notified"))
    (testing "notifying finishes the job"
      (db/mark-background-job-notified! (:job-id job))
      (is (= [] (db/list-unfinished-background-jobs))))
    (testing "deleting the conversation takes its jobs with it"
      (db/save-background-job! {:conversation-id (:id conv) :tool-use-id "t2" :command "x"
                                :pid 1 :process-start-time 1 :log-path "l" :exit-path "e"
                                :started-at "2026-01-01 00:00:00"})
      (db/delete-conversation! (:id conv))
      (is (= [] (db/list-unfinished-background-jobs))))))

(deftest live-calls
  (let [conv (conversation!)]
    (is (= 1 (db/create-live-call! (:id conv))))
    (is (= 2 (db/create-live-call! (:id conv))))
    (db/append-live-call-transcript! {:conversation-id (:id conv) :call 1 :n 1 :user true
                                      :text "hello" :start-ms 0.0 :end-ms 900.0})
    (db/append-live-call-transcript! {:conversation-id (:id conv) :call 1 :n 2 :user false
                                      :text "hi there" :start-ms 900.0 :end-ms 1800.0})
    (let [transcript (db/live-call-transcript (:id conv) 1)]
      (is (= ["hello" "hi there"] (mapv :text transcript)))
      (is (= [true false] (mapv :user transcript))))))

;; ---------------------------------------------------------------------------
;; The API shelley.server already calls

(deftest legacy-api-surface-still-works
  (let [conv (db/create-conversation! {:slug "s" :title "t" :model "m" :working-dir "/tmp"})
        id (:id conv)]
    (testing "create-conversation! takes the old keys and returns the old shape"
      (is (string? id))
      (is (= "s" (:slug conv)))
      (is (= "t" (:title conv)))
      (is (= "m" (:model conv)))
      (is (= "/tmp" (:working_dir conv)))
      (is (string? (:updated_at conv))))
    (testing "get-conversation and list-conversations"
      (is (= id (:id (db/get-conversation id))))
      (is (= [id] (mapv :id (db/list-conversations)))))
    (testing "append-message! takes :role/:content and messages returns them"
      (db/append-message! {:conversation-id id :role :user :content [(llm/text-block "hi")]})
      (db/append-message! {:conversation-id id :role :assistant :content [(llm/text-block "hello")]})
      (is (= [:user :assistant] (mapv :role (db/messages id))))
      (is (= [{:type :text :text "hi"}] (-> (db/messages id) first :content))))
    (testing "touch! sets the title when given one and keeps it otherwise"
      (db/touch! id "new title")
      (is (= "new title" (:title (db/get-conversation id))))
      (db/touch! id nil)
      (is (= "new title" (:title (db/get-conversation id)))))
    (testing "delete removes the messages with it"
      (db/delete-conversation! id)
      (is (nil? (db/get-conversation id)))
      (is (= [] (db/transcript id))))))

(deftest iframes
  (let [conv (conversation!)]
    (db/save-iframe! {:id "iframe-1" :conversation-id (:id conv) :title "chart" :html "<p>hi</p>"})
    (is (= "<p>hi</p>" (:html (db/get-iframe "iframe-1"))))
    (is (= 1 (count (db/list-iframes (:id conv)))))
    (testing "saving the same id replaces it"
      (db/save-iframe! {:id "iframe-1" :conversation-id (:id conv) :title "chart" :html "<p>new</p>"})
      (is (= "<p>new</p>" (:html (db/get-iframe "iframe-1")))))
    (is (nil? (db/get-iframe "missing")))))

(deftest attachments-are-metadata
  (let [conv (conversation!)]
    (db/append-message! {:conversation-id (:id conv) :role :user
                         :content [(llm/text-block "see attached")
                                   {:type :image :media-type "image/png" :data "aGk="}]
                         :attachments [{:name "shot.png" :media-type "image/png" :size 3}]})
    (let [msg (first (db/messages (:id conv)))]
      (is (= [{:name "shot.png" :media-type "image/png" :size 3}]
             (db/message-attachments (:message-id msg))))
      (is (= :image (-> msg :content second :type)))
      (is (= "aGk=" (-> msg :content second :data))))))
