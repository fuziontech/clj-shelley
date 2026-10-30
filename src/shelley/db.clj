(ns shelley.db
  "Persistence.

  Messages are append-only: a turn that crashes halfway leaves the transcript
  intact, and resuming is just reading the rows back. Content blocks are stored
  as JSON because the block vocabulary is the model's, not SQL's."
  (:require [shelley.llm :as llm]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def spec
  (delay
    (let [path (or (System/getenv "SHELLEY_DB")
                   (str (System/getProperty "user.home") "/.clj-shelley/shelley.db"))
          f (io/file path)]
      (.mkdirs (.getParentFile f))
      (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname (.getPath f)})]
        (jdbc/execute! ds ["PRAGMA journal_mode=WAL"])
        (jdbc/execute! ds ["PRAGMA busy_timeout=5000"])
        ds))))

(defn ds [] @spec)

(defn init! []
  (jdbc/execute! (ds) ["CREATE TABLE IF NOT EXISTS conversations (
      id TEXT PRIMARY KEY,
      slug TEXT,
      title TEXT,
      model TEXT,
      working_dir TEXT,
      created_at INTEGER,
      updated_at INTEGER)"])
  (jdbc/execute! (ds) ["CREATE TABLE IF NOT EXISTS messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      conversation_id TEXT NOT NULL,
      role TEXT NOT NULL,
      content TEXT NOT NULL,
      created_at INTEGER NOT NULL)"])
  (jdbc/execute! (ds) ["CREATE INDEX IF NOT EXISTS messages_conv
                        ON messages (conversation_id, id)"])
  (jdbc/execute! (ds) ["CREATE TABLE IF NOT EXISTS iframes (
      id TEXT PRIMARY KEY, conversation_id TEXT, title TEXT, html TEXT, created_at INTEGER)"]))

(defn now [] (System/currentTimeMillis))

(defn get-conversation [id]
  (jdbc/execute-one! (ds) ["SELECT * FROM conversations WHERE id = ?" id]
                     {:builder-fn rs/as-unqualified-lower-maps}))

(defn create-conversation!
  [{:keys [slug title model working-dir]}]
  (let [id (str (java.util.UUID/randomUUID))
        t (now)]
    (jdbc/execute! (ds) ["INSERT INTO conversations (id, slug, title, model, working_dir, created_at, updated_at)
                          VALUES (?,?,?,?,?,?,?)"
                         id slug title model (or working-dir (System/getProperty "user.dir")) t t])
    (get-conversation id)))

(defn list-conversations []
  (jdbc/execute! (ds) ["SELECT * FROM conversations ORDER BY updated_at DESC LIMIT 100"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn touch! [id title]
  (jdbc/execute! (ds) ["UPDATE conversations SET updated_at = ?, title = COALESCE(?, title) WHERE id = ?"
                       (now) title id]))

(defn append-message!
  "Persist one message. Returns the stored row."
  [{:keys [conversation-id role content]}]
  (jdbc/execute-one! (ds) ["INSERT INTO messages (conversation_id, role, content, created_at)
                            VALUES (?,?,?,?)"
                           conversation-id (name role) (json/generate-string content) (now)])
  (touch! conversation-id nil))

(defn messages [conversation-id]
  (mapv (fn [row]
          {:role (keyword (:role row))
           :content (mapv llm/normalize-block
                          (json/parse-string (:content row) true))})
        (jdbc/execute! (ds) ["SELECT role, content FROM messages WHERE conversation_id = ? ORDER BY id"
                             conversation-id]
                       {:builder-fn rs/as-unqualified-lower-maps})))

(defn save-iframe! [{:keys [conversation-id id title html]}]
  (jdbc/execute! (ds) ["INSERT OR REPLACE INTO iframes (id, conversation_id, title, html, created_at)
                        VALUES (?,?,?,?,?)"
                       id conversation-id title html (now)]))

(defn get-iframe [id]
  (jdbc/execute-one! (ds) ["SELECT * FROM iframes WHERE id = ?" id]
                     {:builder-fn rs/as-unqualified-lower-maps}))
