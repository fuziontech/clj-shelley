(ns shelley.server-test
  "The HTTP surface, tested the way a client uses it: a real server on a real
  port, real requests, and assertions on the JSON and the SSE frames.

  The model is stubbed at `shelley.llm/complete`, the one function the loop
  calls for a completion. That keeps the tests offline and fast while leaving
  the whole stack — router, handlers, runtime, persistence, streams — real."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shelley.db :as db]
            [shelley.llm :as llm]
            [shelley.server :as server])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.time Duration)))

;; ---------------------------------------------------------------------------
;; Fixture: a fresh database and a server on an unused port

(defonce ^:private test-env (atom nil))

(defn- tmp-db-path []
  (str (Files/createTempFile "clj-shelley-test" ".db" (make-array java.nio.file.attribute.FileAttribute 0))
       "-unused"))

(defn- base-url [] (str "http://127.0.0.1:" (:port @test-env)))

(def ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- request
  "One HTTP call. Returns {:status n :headers m :body string}."
  [method path & {:keys [body content-type timeout]
                  :or {content-type "application/json" timeout 60}}]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str (base-url) path)))
                    (.timeout (Duration/ofSeconds timeout)))
        publisher (if body
                    (HttpRequest$BodyPublishers/ofString (if (string? body)
                                                           body
                                                           (json/generate-string body)))
                    (HttpRequest$BodyPublishers/noBody))
        builder (.method builder (str/upper-case (name method)) publisher)
        builder (if body (.header builder "content-type" content-type) builder)
        resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)]) (.map (.headers resp))))
     :body (.body resp)}))

(defn- json-body [{:keys [body]}]
  (when-not (str/blank? body)
    (try (json/parse-string body true) (catch Exception _ nil))))

(defn- get* [path & opts] (apply request :get path opts))
(defn- post* [path body & opts] (apply request :post path (concat [:body body] opts)))

(defn- fixture [f]
  (let [path (tmp-db-path)
        tmp-file (java.io.File. path)
        _ (.deleteOnExit tmp-file)]
    ;; The root of `db/db-path` is rebound, not the dynamic thread-local: the
    ;; request handlers run on http-kit threads and must see the same database.
    (with-redefs [db/db-path (constantly path)]
      (db/init!)
      (let [srv (server/start! 0)
            port (org.httpkit.server/server-port srv)]
        (reset! test-env {:port port :server srv :db path})
        (try
          (f)
          (finally
            (server/stop!)
            (reset! test-env nil)))))))

(use-fixtures :once fixture)

;; Progress lines, so a hanging test is identifiable from the log rather than
;; from an absence of output.
(defmethod clojure.test/report :begin-test-var [m]
  (println "TEST>" (-> m :var meta :name)))

;; ---------------------------------------------------------------------------
;; A stubbed model

(defn- assistant
  "A completed response with one text block, delivered through the streaming
  callback so the SSE path is exercised too."
  ([text] (assistant text {}))
  ([text {:keys [usage]}]
   (fn [request]
     (let [on-event (:on-event request)]
       (when on-event
         (on-event {:type :text-delta :text (str text)})))
     {:content [(llm/text-block (str text))]
      :stop-reason :stop
      :usage (or usage {:input_tokens 10 :output_tokens 5})
      :model (:model request)})))

(defn- stub-model!
  "Run body with `shelley.llm/complete` replaced. The turn runs on another
  thread, so the stub must stay installed for as long as the turn runs."
  [f body]
  (with-redefs [llm/complete f] (body)))

;; ---------------------------------------------------------------------------
;; SSE

(defn- start-reader!
  "Read SSE frames into `events` until the stream closes or `closed?` is set.
  One reader per stream: two threads reading the same BufferedReader would eat
  each other's lines."
  [in events closed?]
  (future
    (try
      (loop []
        (when-let [line (and (not @closed?) (.readLine ^java.io.BufferedReader in))]
          (when (str/starts-with? line "data: ")
            (swap! events conj (try (json/parse-string (subs line 6) true)
                                    (catch Exception _ nil))))
          (recur)))
      (catch Exception _ nil))))

(defn- open-stream
  "Open an SSE stream. The body never ends, so it is read by a background
  thread and the test polls the frames it has collected."
  [path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str (base-url) path)))
                (.timeout (Duration/ofSeconds 30))
                (.header "accept" "text/event-stream")
                (.GET)
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofInputStream))
        in (io/reader (.body resp))
        events (atom [])
        closed? (atom false)]
    (start-reader! in events closed?)
    {:status (.statusCode resp)
     :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)]) (.map (.headers resp))))
     :reader in
     :closed? closed?
     :events events}))

(defn- content-type [stream] (get-in stream [:headers "content-type"]))

(defn- close-stream
  "Best effort: closing a BufferedReader blocks behind the reader thread's own
  lock — a deadlock, not a slow close — so the close happens on a throwaway
  thread and the reader checks the flag between frames. A stream that cannot be
  closed costs a leaked socket, not a hung suite."
  [stream]
  (when-let [c (:closed? stream)] (reset! c true))
  (when-let [r (:reader stream)]
    (future (try (.close ^java.io.BufferedReader r) (catch Exception _ nil))))
  nil)

(defn- collect-until
  "Wait until one of the collected frames matches `pred`, then return all of
  them. Times out rather than blocking: a stream that stops producing frames is
  a test failure, not a hung suite."
  [stream pred & {:keys [timeout-ms] :or {timeout-ms 45000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [events @(:events stream)]
        (if (or (some pred events) (> (System/currentTimeMillis) deadline))
          events
          (do (Thread/sleep 50) (recur)))))))

(defn- text-of
  "The text a client would render from a message row: llm_data is a JSON
  string, not an object."
  [message]
  (let [data (try (json/parse-string (:llm_data message) true) (catch Exception _ nil))]
    (->> (:content data)
         (map :text)
         (remove nil?)
         (str/join ""))))

(defn- chat! [conv-id text]
  (post* (str "/api/conversation/" conv-id "/chat") {"message" text}))

(defn- new-conversation! [& [body]]
  (post* "/api/conversations/new" (merge {"cwd" "/tmp"} body)))

(defn- conv-id [resp] (get-in (json-body resp) [:conversation :id]))

(defn- flag-value
  "A flag's value from the `flags` map. JSON object keys are keywordised on the
  way in, so the name may arrive as `:performance-hud`."
  [flags flag-name]
  ;; `keep`, not `some`: a flag whose value is false must not read as missing.
  (->> flags
       (keep (fn [[k v]] (when (= flag-name (name k)) v)))
       first))

(defn- wait-idle
  "Poll until the conversation is not running. Returns the last GET body."
  [id & {:keys [timeout-ms] :or {timeout-ms 20000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [r (get* (str "/api/conversation/" id))
            d (json-body r)]
        (if (or (not (:running d)) (> (System/currentTimeMillis) deadline))
          d
          (do (Thread/sleep 50) (recur)))))))

;; ---------------------------------------------------------------------------
;; Every route is reachable through the router

(deftest route-table-is-consistent
  (testing "every registered pattern matches itself for each of its methods"
    (doseq [[pattern methods] server/routes
            [method _] methods]
      (let [path (-> pattern
                     (str/replace #":[\w-]+" "x")
                     (str/replace #"\*" "x"))
            match (#'server/match-route method path)]
        (is (some? match) (str "no match for " (name method) " " path)))))
  (testing "a pattern with parameters captures them"
    (is (= {:id "abc"} (second (#'server/match-route :get "/api/conversation/abc")))))
  (testing "an unknown path matches nothing"
    (is (nil? (#'server/match-route :get "/api/nope")))))

(deftest every-route-answers-json
  (testing "every route answers, and failures are JSON rather than a stack trace"
    (doseq [[pattern methods] server/routes
            [method _] methods
            :when (not-any? #(str/includes? pattern %)
                            [;; streams and websockets never return
                             "stream" "events" "exec-ws" "/live-session" "call/"
                             ;; these reach the network or a child process
                             "custom-models-test" "/integrations/" "/mcp/" "upgrade"])]
      (let [path (-> pattern
                     (str/replace #":[\w-]+" "does-not-exist")
                     (str/replace #"\*" "x"))
            resp (try
                   (request method path :timeout 10
                            :body (when (#{:post :put :patch} method) {}))
                   (catch Exception e {:status 0 :error (.getMessage e)}))
            ct (str (get-in resp [:headers "content-type"]))
            label (str (name method) " " pattern)]
        (is (not= 0 (:status resp)) (str label " did not answer: " (:error resp)))
        (is (or (str/includes? ct "json") (str/includes? ct "html"))
            (str label " -> " ct))
        (when (>= (:status resp) 400)
          (is (map? (json-body resp)) (str label " answered with a non-JSON error"))
          (is (string? (:error (json-body resp)))
              (str label " error body has no error field: " (subs (str (:body resp)) 0 (min 120 (count (str (:body resp))))))))))))

;; ---------------------------------------------------------------------------
;; Creating, reading back

(deftest create-and-read
  (let [resp (new-conversation! {"conversation_options" {"thinking_level" "low"}})
        id (conv-id resp)]
    (is (= 201 (:status resp)) (:body resp))
    (testing "the row carries the fields a list needs"
      (let [row (get-in (json-body resp) [:conversation])]
        (is (some? (get row :id)))
        (is (= true (contains? row :conversation_id)))
        (is (contains? row :tags))
        (is (contains? row :archived))
        (is (contains? row :preview))
        (is (contains? row :agent_working))))
    (testing "reading it back returns the same conversation and no messages"
      (let [d (json-body (get* (str "/api/conversation/" id)))]
        (is (= id (get-in d [:conversation :id])))
        (is (= [] (:messages d)))
        (is (false? (:running d)))))
    (testing "the plural id path and the singular one agree"
      (is (= id (get-in (json-body (get* (str "/api/conversations/" id))) [:conversation :id]))))
    (testing "cwd is honoured"
      (is (= "/tmp" (get-in (json-body (get* (str "/api/conversation/" id)))
                            [:conversation :cwd]))))
    (testing "POST /api/conversations is the same creation path"
      (is (= 201 (:status (post* "/api/conversations" {"cwd" "/tmp"})))))))

(deftest create-with-a-message-runs-the-turn
  (stub-model! (assistant "hello there")
               (fn []
                 (let [resp (new-conversation! {"message" "say hello"})
                       id (conv-id resp)]
                   (is (= 201 (:status resp)))
                   (let [d (wait-idle id)
                         msgs (:messages d)]
                     (is (>= (count msgs) 2) "user message plus the reply")
                     (is (= "say hello" (text-of (first msgs))))
                     (is (some #(= "hello there" (text-of %)) (rest msgs))
                         "the turn ran and its reply was persisted"))))))

;; ---------------------------------------------------------------------------
;; Chat, streaming, persistence

(deftest chat-streams-and-persists
  (stub-model! (assistant "streamed reply")
               (fn []
                 (let [id (conv-id (new-conversation!))
                       stream (open-stream (str "/api/conversation/" id "/stream"))]
                   (testing "the stream opens as event-stream"
                     (is (str/includes? (str (content-type stream)) "text/event-stream")))
                   (testing "the first frame is the history"
                     (let [events (collect-until stream #(= "history" (:type %)))]
                       (is (= "history" (:type (first events))))
                       (is (contains? (first events) :messages))))
                   (testing "chat is accepted"
                     (let [resp (chat! id "say something")]
                       (is (= 202 (:status resp)))
                       (is (= "accepted" (:status (json-body resp))))))
                   (testing "the turn streams deltas and an end"
                     (let [events (collect-until stream #(= "turn-end" (:type %)))]
                       (is (some #(= "text-delta" (:type %)) events) "a text delta arrived")
                       (is (some #(= "turn-end" (:type %)) events) "the turn ended")))
                   (close-stream stream)
                   (testing "the transcript has the user turn and the reply"
                     (let [d (wait-idle id)
                           msgs (:messages d)]
                       (is (= 2 (count msgs)))
                       (is (= "user" (get-in msgs [0 :type])))
                       (is (= "say something" (text-of (first msgs))))
                       (is (= "streamed reply" (text-of (second msgs))))
                       (is (pos? (:max_sequence_id d)))))
                   (testing "llm_data is a JSON string"
                     (let [m (first (:messages (json-body (get* (str "/api/conversation/" id)))))]
                       (is (string? (:llm_data m)))))))))

(deftest streaming-resumes-from-a-sequence-id
  (stub-model! (assistant "first")
               (fn []
                 (let [id (conv-id (new-conversation!))]
                   (chat! id "one")
                   (let [d (wait-idle id)
                         max-seq (:max_sequence_id d)
                         stream (open-stream (str "/api/conversation/" id "/stream?last_sequence_id=1"))
                         events (collect-until stream #(true? (:snapshot_complete %)))]
                     (is (seq events))
                     (let [history (first events)]
                       (is (= "history" (:type history)))
                       (is (every? #(> (:sequence_id %) 1) (:messages history))
                           "only messages after the resume point are replayed")
                       (is (pos? max-seq)))
                     (close-stream stream))))))

(deftest queue-while-a-turn-runs
  (let [release (promise)]
    (stub-model!
     (fn [request]
       @release
       (let [on-event (:on-event request)]
         (when on-event (on-event {:type :text-delta :text "slow reply"})))
       {:content [(llm/text-block "slow reply")] :stop-reason :stop
        :usage {:input_tokens 1 :output_tokens 1} :model (:model request)})
     (fn []
       (let [id (conv-id (new-conversation!))]
         (chat! id "first")
         ;; Wait until the loop is actually in the model call.
         (let [deadline (+ (System/currentTimeMillis) 10000)]
           (loop []
             (when (and (not (:running (json-body (get* (str "/api/conversation/" id)))))
                        (< (System/currentTimeMillis) deadline))
               (Thread/sleep 50)
               (recur))))
         (is (:running (json-body (get* (str "/api/conversation/" id)))))
         (testing "a second message is queued, not refused"
           (let [resp (chat! id "second")]
             (is (= 202 (:status resp)))
             (is (= "queued" (:status (json-body resp))))
             (is (some? (:queued_id (json-body resp))))))
         (deliver release true)
         (let [d (wait-idle id :timeout-ms 30000)
               texts (map text-of (:messages d))]
           (is (>= (count texts) 3) "user, reply, queued user")
           (is (some #(= "second" %) texts) "the queued message reached the transcript")))))))

(deftest cancel-stops-a-turn
  (let [release (promise)]
    (stub-model! (fn [request]
                   (try @release (catch Exception _ nil))
                   {:content [(llm/text-block "late")] :stop-reason :stop
                    :usage {} :model (:model request)})
                 (fn []
                   (let [id (conv-id (new-conversation!))]
                     (chat! id "do something slow")
                     (let [r (post* (str "/api/conversation/" id "/cancel") {})]
                       (is (contains? #{200 202} (:status r)) (:body r)))
                     (deliver release true)
                     (wait-idle id :timeout-ms 20000))))))

;; ---------------------------------------------------------------------------
;; Listing, searching, tagging, archiving

(deftest archive-hides-from-the-main-list
  (let [id (conv-id (new-conversation!))
        listed? (fn [path] (some #(= id (get % :id))
                                 (:conversations (json-body (get* path)))))]
    (is (listed? "/api/conversations"))
    (is (= 200 (:status (post* (str "/api/conversation/" id "/archive") {"archived" true}))))
    (is (not (listed? "/api/conversations")))
    (is (some #(= id (get % :id)) (:conversations (json-body (get* "/api/conversations/archived")))))
    (post* (str "/api/conversation/" id "/archive") {"archived" false})
    (is (listed? "/api/conversations"))
    (post* (str "/api/conversation/" id "/archive") {"archived" true})
    (post* (str "/api/conversation/" id "/unarchive") {})
    (is (listed? "/api/conversations"))))

(deftest archive-all-clears-the-active-list
  (let [a (conv-id (new-conversation!))
        b (conv-id (new-conversation!))]
    (testing "one call archives the active conversations and reports how many"
      (let [resp (post* "/api/conversations/archive-all" {})
            body (json-body resp)]
        (is (= 200 (:status resp)) (:body resp))
        (is (true? (:archived body)))
        (is (pos? (:count body)))
        (is (not (contains? (set (map :id (:conversations (json-body (get* "/api/conversations")))))
                            a)))))
    (testing "both conversations are in the archived list"
      (let [ids (set (map :id (:conversations (json-body (get* "/api/conversations/archived")))))]
        (is (contains? ids a))
        (is (contains? ids b))))
    (testing "a second call is idempotent"
      (is (= 0 (:count (json-body (post* "/api/conversations/archive-all" {}))))))
    (testing "an unarchived conversation is restored by the per-id endpoint"
      (is (= 200 (:status (post* (str "/api/conversation/" a "/unarchive") {}))))
      (is (some #(= a (get % :id))
                (:conversations (json-body (get* "/api/conversations"))))))))

(deftest search-finds-the-slug
  (let [id (conv-id (new-conversation!))
        _ (post* (str "/api/conversation/" id "/rename") {"slug" "needle-in-a-haystack"})
        hits (:conversations (json-body (get* "/api/conversations?q=needle")))]
    (is (some #(= id (get % :id)) hits))))

(deftest search-finds-message-text
  (stub-model! (assistant "ok")
               (fn []
                 (let [id (conv-id (new-conversation!))]
                   (chat! id "the zanzibarian protocol is ready")
                   (wait-idle id)
                   (let [hits (:conversations (json-body (get* "/api/conversations?q=zanzibarian")))]
                     (is (some #(= id (get % :id)) hits) "message text is searchable"))))))

(deftest tags-round-trip
  (let [id (conv-id (new-conversation!))]
    (is (= 200 (:status (post* (str "/api/conversation/" id "/tags") {"add" ["alpha" "beta"]}))))
    (is (= #{"alpha" "beta"}
           (set (:tags (json-body (get* (str "/api/conversation/" id "/tags")))))))
    (post* (str "/api/conversation/" id "/tags") {"remove" ["beta"]})
    (is (= ["alpha"] (:tags (json-body (get* (str "/api/conversation/" id "/tags"))))))
    (testing "the snapshot rows carry the tags"
      (let [row (->> (:conversations (json-body (get* "/api/conversations/snapshot")))
                     (filter #(= id (get % :id)))
                     first)]
        (is (= ["alpha"] (:tags row)))))))

(deftest change-dir-persists
  (let [id (conv-id (new-conversation!))
        dir "/home"
        resp (post* (str "/api/conversation/" id "/cwd") {"cwd" dir})]
    (is (= 200 (:status resp)) (:body resp))
    (testing "the API reports the new directory"
      (is (= dir (get-in (json-body (get* (str "/api/conversation/" id)))
                         [:conversation :cwd]))))
    (testing "and the database row does too"
      (is (= dir (:working_dir (db/get-conversation id))))))
  (testing "a directory that does not exist is refused"
    (let [id (conv-id (new-conversation!))
          resp (post* (str "/api/conversation/" id "/cwd") {"cwd" "/no/such/place"})]
      (is (= 400 (:status resp)))
      (is (string? (:error (json-body resp)))))))

(deftest rename-and-slug-lookup
  (let [id (conv-id (new-conversation!))]
    (is (= 200 (:status (post* (str "/api/conversation/" id "/rename") {"slug" "My Chat"}))))
    (is (= id (get-in (json-body (get* "/api/conversation-by-slug/my-chat"))
                      [:conversation :id])))
    (testing "the slug is unique"
      (let [other (conv-id (new-conversation!))]
        (is (= 409 (:status (post* (str "/api/conversation/" other "/rename")
                                   {"slug" "my-chat"}))))))))

;; ---------------------------------------------------------------------------
;; Errors are JSON

(deftest errors-are-json
  (testing "unknown conversation"
    (let [r (get* "/api/conversation/nope")]
      (is (= 404 (:status r)))
      (is (str/includes? (get-in r [:headers "content-type"]) "json"))))
  (testing "malformed JSON body"
    (let [r (request :post "/api/conversation/whatever/chat" :body "{not json")]
      (is (= 400 (:status r)))
      (is (= "invalid JSON body" (:error (json-body r))))))
  (testing "blank message"
    (let [id (conv-id (new-conversation!))]
      (is (= 400 (:status (post* (str "/api/conversation/" id "/chat") {"message" "  "}))))))
  (testing "an unknown path"
    (let [r (get* "/api/does-not-exist")]
      (is (= 404 (:status r)))
      (is (str/includes? (str (:error (json-body r))) "no route")))))

;; ---------------------------------------------------------------------------
;; Snapshot and the list patch stream

(deftest snapshot-hash-is-stable
  (let [a (json-body (get* "/api/conversations/snapshot"))
        b (json-body (get* "/api/conversations/snapshot"))]
    (is (string? (:hash a)))
    (is (= (:hash a) (:hash b)) "no change means no new hash")
    (is (vector? (:conversations a)))
    (is (= (count (:conversations a)) (:count a)))))

(deftest stream2-publishes-patches
  (let [stream (open-stream "/api/stream2")
        first-events (collect-until stream #(= "history" (:type %)))]
    (is (= 200 (:status stream)))
    (is (= "history" (:type (first first-events))))
    (is (string? (:hash (first first-events))))
    (testing "creating a conversation produces a patch"
      (let [id (conv-id (new-conversation!))
            events (collect-until stream #(= "conversation_list_patch" (:type %)))]
        (is (some #(= "conversation_list_patch" (:type %)) events))
        (let [patch (some #(when (= "conversation_list_patch" (:type %)) %) events)]
          (is (some? (:patch patch)))
          (is (some? (:old_hash patch)))
          (is (some? (:new_hash patch)))
          (is (not= (:old_hash patch) (:new_hash patch)))
          (testing "the patch adds the new row"
            (is (some (fn [op] (and (= "add" (:op op))
                                    (str/includes? (json/generate-string (:value op)) id)))
                      (:patch patch))))))
    (close-stream stream))))

;; ---------------------------------------------------------------------------
;; the surfaces the UI talks to

(deftest models-and-settings
  (let [models (json-body (get* "/api/models"))
        providers (json-body (get* "/api/model-providers"))]
    (is (vector? (:models models)))
    (is (vector? (:providers providers)))
    (is (every? string? (map :provider_id (:providers providers))))
    (is (some? (:default_model models))))
  (testing "settings respond with the shape the panel expects"
    (let [s (json-body (get* "/api/settings"))]
      (is (map? s))
      (is (contains? s :settings))))
  (testing "a setting can be written and read back"
    (is (= 200 (:status (post* "/api/settings" {"key" "exe_notify" "value" false}))))))

(deftest feature-flags-round-trip
  ;; `flags` is the name->value map the UI indexes directly; `registry` is the
  ;; list with descriptions and defaults.
  (let [body (json-body (get* "/api/feature-flags"))]
    (is (map? (:flags body)) "flags is the map a settings panel indexes")
    (is (some #(= "performance-hud" (:name %)) (:registry body))
        "the registry describes every flag"))
  (is (= 200 (:status (post* "/api/feature-flags" {"name" "performance-hud" "value" true}))))
  (is (true? (flag-value (:flags (json-body (get* "/api/feature-flags"))) "performance-hud")))
  (is (= 200 (:status (request :delete "/api/feature-flags" :body {"name" "performance-hud"}))))
  (is (false? (flag-value (:flags (json-body (get* "/api/feature-flags"))) "performance-hud"))
      "deleting the override falls back to the default"))

(deftest workspace-endpoints-take-query-parameters
  (testing "file reads honour ?path="
    (let [r (get* "/api/file?path=README.md")]
      (is (= 200 (:status r)) (:body r))
      (is (str/includes? (str (:content (json-body r))) "clj-shelley"))))
  (testing "git status paths are whole paths"
    (let [rows (:changed (json-body (get* "/api/git/status")))]
      (is (vector? rows))
      (is (every? #(not (str/starts-with? (:path %) "e")) rows))))
  (testing "escaping the workspace is refused"
    (is (= 403 (:status (get* "/api/file?path=../../etc/passwd"))))))

(deftest upload-and-attachment
  (let [r (post* "/api/upload" {"name" "note.txt"
                                "content" (.encodeToString (java.util.Base64/getEncoder)
                                                           (.getBytes "hello upload"))})
        d (json-body r)]
    (is (= 200 (:status r)) (:body r))
    (is (string? (:path d)))
    (is (= "hello upload" (slurp (:path d))))))

(deftest delete-is-idempotent
  (let [id (conv-id (new-conversation!))]
    (is (= 200 (:status (post* (str "/api/conversation/" id "/delete") {}))))
    (is (= 404 (:status (get* (str "/api/conversation/" id))))
        "a deleted conversation is gone")
    (testing "deleting twice still answers, without an error page"
      (is (contains? #{200 404} (:status (post* (str "/api/conversation/" id "/delete") {})))))))

(deftest version-exposes-capabilities
  (let [v (json-body (get* "/version"))]
    (is (string? (:tag v)))
    (is (vector? (:capabilities v)))
    (is (some #{"stream2-patches"} (:capabilities v)))))
