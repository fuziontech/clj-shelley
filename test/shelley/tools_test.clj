(ns shelley.tools-test
  "Tests for the tool layer: the registry contract, bash jobs, file tools,
  skills, the MCP client and the two UI-facing tools."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shelley.tools :as tools]
            [shelley.tools.bash-job :as bash-job]
            [shelley.tools.browse :as browse]
            [shelley.tools.cdp :as cdp]
            [shelley.tools.compact :as compact]
            [shelley.tools.files :as files]
            [shelley.tools.mcp :as mcp]
            [shelley.tools.message-user :as message-user]
            [shelley.tools.skills :as skills]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- temp-dir [prefix]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str prefix "-" (subs (str (java.util.UUID/randomUUID)) 0 8)))]
    (.mkdirs d)
    d))

(defn- wait-until
  "Poll `pred` until it is true or the deadline passes. Returns the last value."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (let [v (pred)]
        (cond
          v v
          (> (System/currentTimeMillis) deadline) v
          :else (do (Thread/sleep 25) (recur)))))))

(defn- ctx [dir & {:as extra}]
  (merge {:working-dir (atom (str dir))} extra))

;; ---------------------------------------------------------------------------
;; Registry contract

(deftest every-tool-is-well-formed
  (testing "static registry entries carry name, description, schema and handler"
    (doseq [[k tool] tools/all-tools]
      (is (= k (:name tool)) (str "registry key matches :name for " k))
      (is (string? (:name tool)))
      (is (seq (str/trim (str (:description tool)))) (str k " has a description"))
      (is (= "object" (get-in tool [:input-schema :type])) (str k " has an object schema"))
      (is (map? (get-in tool [:input-schema :properties])) (str k " has properties"))
      (is (fn? (:handler tool)) (str k " has a handler")))
    (is (pos? (count tools/all-tools)))))

(deftest tool-defs-shape
  (testing "tool-defs produces API definitions the LLM layer accepts"
    (let [defs (tools/tool-defs :default)]
      (is (pos? (count defs)))
      (is (apply distinct? (map :name defs)) "tool names are unique")
      (doseq [d defs]
        (is (string? (:name d)))
        (is (seq (:description d)) (str (:name d) " has a description"))
        (is (= "object" (get-in d [:input-schema :type])) (str (:name d) " schema is an object")))))

  (testing "the readonly toolset drops tools that write"
    (let [names (set (map :name (tools/tool-defs :readonly)))]
      (is (contains? names "read_file"))
      (is (not (contains? names "write_file")))
      (is (not (contains? names "message_user")))
      (is (not (contains? names "patch"))))))

(deftest execute-contract
  (let [c (ctx (System/getProperty "java.io.tmpdir"))]
    (testing "always a tool-result, even for an unknown tool"
      (let [r (tools/execute c {:id "x" :name "nope" :input {}})]
        (is (= :tool-result (:type r)))
        (is (= "x" (:tool-use-id r)))
        (is (true? (:is-error r)))))

    (testing "a throwing handler becomes an error result, not an exception"
      (let [r (tools/execute c {:id "y" :name "read_file" :input {:path "/definitely/not/here"}})]
        (is (= :tool-result (:type r)))
        (is (true? (:is-error r)))
        (is (str/includes? (-> r :content first :text) "no such file"))))

    (testing "string arguments are keywordised at the boundary"
      (let [r (tools/execute c {:id "z" :name "bash" :input {"command" "echo keyed"}})]
        (is (str/includes? (-> r :content first :text) "keyed"))))))

;; ---------------------------------------------------------------------------
;; bash: foreground

(deftest bash-foreground
  (let [dir (temp-dir "bash-fg")]
    (testing "runs in the working directory and returns output"
      (let [r (tools/run-bash (ctx dir) {:command "pwd"})]
        (is (not (:is-error r)))
        (is (= (str dir) (str/trim (-> r :content first :text))))))

    (testing "a non-zero exit is an error result carrying the code"
      (let [r (tools/run-bash (ctx dir) {:command "echo oops >&2; exit 3"})]
        (is (true? (:is-error r)))
        (is (str/includes? (-> r :content first :text) "exit 3"))
        (is (str/includes? (-> r :content first :text) "oops"))))

    (testing "stderr is merged into the output"
      (let [text (-> (tools/run-bash (ctx dir) {:command "echo to-stderr 1>&2"}) :content first :text)]
        (is (str/includes? text "to-stderr"))))

    (testing "very large output is truncated in the middle"
      (let [text (-> (tools/run-bash (ctx dir) {:command "seq 1 40000"}) :content first :text)]
        (is (< (count text) 41000))
        (is (str/includes? text "chars omitted"))))))

;; ---------------------------------------------------------------------------
;; bash: background jobs

(deftest background-job-lifecycle
  (let [dir (temp-dir "bash-bg")]
    (testing "a job writes a log and records its exit code"
      (let [job (bash-job/start! {:command "echo started; sleep 0.2; echo finished"
                                  :working-dir (str dir)})
            {:keys [finished? exit-code]} (bash-job/wait! job 20000)
            outcome (bash-job/outcome job)]
        (is finished? "the job exits")
        (is (= 0 exit-code))
        (is (= 0 (bash-job/exit-code job)))
        (is (str/includes? (:tail outcome) "finished"))
        (is (.exists (io/file (:log-path job))))))

    (testing "a killed job reports a signal exit code"
      (let [job (bash-job/start! {:command "sleep 30" :working-dir (str dir)})]
        (is (= :killed (bash-job/kill! job)))
        (let [{:keys [finished? exit-code]} (bash-job/wait! job 20000)]
          (is finished? "kill makes the wrapper exit")
          (is (contains? #{137 143} exit-code) (str "exit code was " exit-code)))))

    (testing "the tool returns a job handle immediately when backgrounded"
      (let [r (tools/run-bash (ctx dir) {:command "sleep 2; echo late" :background true})
            text (-> r :content first :text)]
        (is (not (:is-error r)))
        (is (str/includes? text "background job"))
        (is (str/includes? text "Log:"))))

    (testing "completion is surfaced as a :background-job-completion event"
      (let [events (atom [])
            job (bash-job/start! {:command "sleep 0.3; echo done"
                                  :working-dir (str dir)
                                  :conversation-id "conv-test"
                                  :tool-use-id "tool-1"})
            text (bash-job/background! job {:emit #(swap! events conj %)} "Started")
            ev (wait-until #(first (filter (fn [e] (= :background-job-completion (:type e))) @events))
                           15000)]
        (is (str/includes? text "background job"))
        (is (some? ev) "the completion event fires")
        (is (= 0 (:exit-code ev)))
        (is (= "conv-test" (:conversation-id ev)))
        (is (str/includes? (:text ev) "finished"))
        (is (= (:job-id ev) (get-in (bash-job/completion-message ev) [:user-data :background-job-id]))
            "completion-message carries the job id as user-data")))

    (testing "list-jobs only shows this conversation"
      (let [job (bash-job/start! {:command "sleep 5" :working-dir (str dir)
                                  :conversation-id "conv-list"})]
        (is (some #(= (:id job) (:job-id %)) (bash-job/list-jobs "conv-list")))
        (is (not-any? #(= (:id job) (:job-id %)) (bash-job/list-jobs "other")))
        (bash-job/kill! job)
        (Thread/sleep 300)
        (bash-job/forget! job)))))

(deftest background-job-with-injection
  (testing "an inject-message callback receives the user message"
    (let [injected (promise)
          job (bash-job/start! {:command "echo injected" :working-dir (System/getProperty "java.io.tmpdir")})]
      (bash-job/background! job {:inject-message #(deliver injected %)} "Started")
      (let [msg (deref injected 15000 nil)]
        (is (some? msg) "the loop is handed a user message")
        (is (= :user (:role msg)))
        (is (str/includes? (-> msg :content first :text) "injected"))
        (is (some? (get-in msg [:user-data :background-job-id])))))))

;; ---------------------------------------------------------------------------
;; File tools

(deftest file-tool-edge-cases
  (let [dir (temp-dir "files")]
    (spit (io/file dir "a.txt") "one\ntwo\nthree\n")

    (testing "read_file numbers lines"
      (let [text (files/read-file (ctx dir) {:path "a.txt"})]
        (is (str/includes? text "     1\tone"))
        (is (str/includes? text "     3\tthree"))))

    (testing "read_file honours offset and limit"
      (let [text (files/read-file (ctx dir) {:path "a.txt" :offset 2 :limit 1})]
        (is (str/includes? text "     2\ttwo"))
        (is (not (str/includes? text "three")))))

    (testing "a missing file is a clear error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such file"
                            (files/read-file (ctx dir) {:path "nope.txt"}))))

    (testing "a directory is not a file"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"directory"
                            (files/read-file (ctx dir) {:path "."}))))

    (testing "reading a huge file is bounded"
      (spit (io/file dir "big.txt") (apply str (repeat 5000 "0123456789\n")))
      (let [text (files/read-file (ctx dir) {:path "big.txt"})]
        (is (< (count (str/split-lines text)) 2100))
        (is (str/includes? text "big.txt"))))

    (testing "relative path escape is refused"
      (is (thrown? clojure.lang.ExceptionInfo (files/read-file (ctx dir) {:path "../../../etc/passwd"}))))

    (testing "write_file creates parents and reports the size"
      (let [r (files/write-file (ctx dir) {:path "sub/dir/b.txt" :content "hello"})]
        (is (str/includes? r "b.txt"))
        (is (= "hello" (slurp (io/file dir "sub/dir/b.txt"))))))

    (testing "write_file appends when asked"
      (files/write-file (ctx dir) {:path "sub/dir/b.txt" :content " world" :append true})
      (is (= "hello world" (slurp (io/file dir "sub/dir/b.txt")))))

    (testing "list_directory shows entries, directories first"
      (let [text (files/list-directory (ctx dir) {})]
        (is (str/includes? text "a.txt"))
        (is (str/includes? text "entries"))))

    (testing "list_directory on a file is an error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a directory"
                            (files/list-directory (ctx dir) {:path "a.txt"}))))

    (testing "find_files matches a glob and skips heavy directories"
      (.mkdirs (io/file dir "node_modules"))
      (spit (io/file dir "node_modules/ignored.txt") "x")
      (spit (io/file dir "sub/dir/c.clj") "(ns c)")
      (let [text (files/find-files (ctx dir) {:pattern "**/*.clj"})]
        (is (str/includes? text "c.clj"))
        (is (not (str/includes? text "ignored.txt")))))))

;; ---------------------------------------------------------------------------
;; Skills

(deftest skill-discovery
  (let [dir (temp-dir "skills")
        skill-dir (io/file dir ".shelley" "skills" "test-skill")]
    (.mkdirs skill-dir)
    (spit (io/file skill-dir "SKILL.md")
          (str "---\nname: test-skill\ndescription: A skill used by the test suite.\n---\n\n"
               "# Test skill\n\nDo the thing.\n"))
    (testing "a project skill is discovered, parsed and cat-able"
      (let [found (skills/find-skill (str dir) "test-skill")]
        (is (some? found))
        (is (= "test-skill" (:name found)))
        (is (str/includes? (:description found) "test suite"))
        (is (str/includes? (skills/cat-skill found) "Do the thing."))))

    (testing "the skill tool lists and loads it"
      (let [list-text (-> (skills/run-skills (ctx dir) {:action "list"}) :content first :text)
            cat-text (-> (skills/run-skills (ctx dir) {:action "cat" :name "test-skill"})
                         :content first :text)]
        (is (str/includes? list-text "test-skill"))
        (is (str/includes? cat-text "Do the thing."))))

    (testing "an unknown skill is an error result listing the alternatives"
      (let [r (skills/run-skills (ctx dir) {:action "cat" :name "nope"})]
        (is (true? (:is-error r)))
        (is (str/includes? (-> r :content first :text) "test-skill"))))

    (testing "a directory name that disagrees with the frontmatter is rejected"
      (let [bad (io/file dir ".shelley" "skills" "other-name")]
        (.mkdirs bad)
        (spit (io/file bad "SKILL.md") "---\nname: mismatched\ndescription: x\n---\nbody\n")
        (is (nil? (skills/find-skill (str dir) "mismatched")))))

    (testing "the builtin skills ship with the code"
      (let [names (set (map :name (skills/builtin-skills)))]
        (is (contains? names "commit-hygiene"))
        (is (contains? names "running-tests"))))

    (testing "to-prompt-xml lists skills in a form the model can read"
      (let [xml (skills/to-prompt-xml (skills/all-skills (str dir)))]
        (is (str/includes? xml "<available_skills>"))
        (is (str/includes? xml "test-skill"))))))

;; ---------------------------------------------------------------------------
;; MCP client

(def ^:private stub-server
  "A minimal MCP server over stdio: newline-delimited JSON-RPC, no deps."
  (str "let buf='';\n"
       "process.stdin.setEncoding('utf8');\n"
       "process.stdin.on('data', d => {\n"
       "  buf += d; let i;\n"
       "  while ((i = buf.indexOf(String.fromCharCode(10))) >= 0) {\n"
       "    const line = buf.slice(0, i); buf = buf.slice(i + 1);\n"
       "    if (!line.trim()) continue;\n"
       "    let msg; try { msg = JSON.parse(line); } catch (e) { continue; }\n"
       "    handle(msg);\n"
       "  }\n"
       "});\n"
       "function send(o) { process.stdout.write(JSON.stringify(o) + String.fromCharCode(10)); }\n"
       "function handle(msg) {\n"
       "  if (msg.method === 'hang') return;\n"
       "  if (msg.id === undefined || msg.id === null) return;\n"
       "  switch (msg.method) {\n"
       "    case 'initialize': return send({jsonrpc:'2.0', id:msg.id, result:{protocolVersion:'2025-06-18', capabilities:{tools:{}}, serverInfo:{name:'stub', version:'1'}}});\n"
       "    case 'tools/list': return send({jsonrpc:'2.0', id:msg.id, result:{tools:[\n"
       "      {name:'echo', description:'Echo text back', inputSchema:{type:'object', properties:{text:{type:'string'}}, required:['text']}},\n"
       "      {name:'boom', description:'Always fails', inputSchema:{type:'object', properties:{}}}]}});\n"
       "    case 'tools/call': {\n"
       "      const p = msg.params || {}; const args = p.arguments || {};\n"
       "      if (p.name === 'echo') return send({jsonrpc:'2.0', id:msg.id, result:{content:[{type:'text', text:'echo: ' + (args.text || '')}]}});\n"
       "      if (p.name === 'boom') return send({jsonrpc:'2.0', id:msg.id, result:{content:[{type:'text', text:'failed on purpose'}], isError:true}});\n"
       "      return send({jsonrpc:'2.0', id:msg.id, error:{code:-32602, message:'unknown tool ' + p.name}});\n"
       "    }\n"
       "    default: return send({jsonrpc:'2.0', id:msg.id, error:{code:-32601, message:'no such method'}});\n"
       "  }\n"
       "}\n"))

(defn- start-stub-server! []
  (let [f (io/file (temp-dir "mcp-stub") "stub.js")]
    (spit f stub-server)
    (mcp/connect! "stub" {:command "node" :args [(.getPath f)]})))

(use-fixtures :once
  (fn [f]
    (try (f) (finally (mcp/disconnect-all!) (browse/close!)))))

(deftest mcp-json-rpc-round-trip
  (let [session (start-stub-server!)]
    (try
      (testing "initialize negotiated a session"
        (is (some? session))
        (is (= "stub" (get-in @(:server-info session) [:serverInfo :name]))))

      (testing "tools/list is namespaced per server"
        (is (= ["stub.echo" "stub.boom"] (mapv :name (mcp/list-tools! session)))))

      (testing "arguments with newlines survive the newline framing"
        (let [r (mcp/call-tool! "stub.echo" {:text "a\nb"})]
          (is (false? (:is-error r)))
          (is (= "echo: a\nb" (-> r :content first :text)))))

      (testing "a tool-level failure is a result, not an exception"
        (let [r (mcp/call-tool! "stub.boom" {})]
          (is (true? (:is-error r)))
          (is (= "failed on purpose" (-> r :content first :text)))))

      (testing "a JSON-RPC error is raised with the server's message"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown tool missing"
                              (mcp/call-tool! "stub.missing" {}))))

      (testing "a request the server ignores times out instead of hanging forever"
        (is (thrown? clojure.lang.ExceptionInfo
                     (mcp/request! session "hang" {} 400))))

      (testing "MCP tools become agent tools with a working handler"
        (let [defs (mcp/list-tools! session)
              echo (first (filter #(= "stub.echo" (:name %)) defs))]
          (is (fn? (:handler echo)))
          (is (= "object" (get-in echo [:input-schema :type])))
          (is (= "echo: via-handler" (-> ((:handler echo) nil {:text "via-handler"})
                                         :content first :text)))))
      (finally
        (mcp/disconnect! "stub")))))

(deftest mcp-tool-name-parsing
  (is (= {:server "files" :tool "read"} (mcp/parse-tool-name "files.read")))
  (is (= {:server "a" :tool "b.c"} (mcp/parse-tool-name "a.b.c")))
  (is (nil? (mcp/parse-tool-name "nodots")))
  (is (thrown? clojure.lang.ExceptionInfo (mcp/call-tool! "nodots" {}))))

(deftest mcp-tool-defs-are-a-vector
  (let [dir (temp-dir "no-mcp")]
    (is (vector? (mcp/tool-defs (str dir))))))

;; ---------------------------------------------------------------------------
;; compact_in_place

(deftest compact-in-place
  (let [messages [{:role :user :sequence-id "1" :content [{:type :text :text "first question"}]}
                  {:role :assistant :sequence-id "2" :content [{:type :text :text "first answer"}]}
                  {:role :user :sequence-id "3" :content [{:type :text :text "second question"}]}
                  {:role :assistant :sequence-id "4" :content [{:type :text :text "second answer"}]}
                  {:role :user :sequence-id "5" :content [{:type :text :text "third question"}]}
                  {:role :assistant :sequence-id "6" :content [{:type :text :text "third answer"}]}
                  {:role :user :sequence-id "7" :content [{:type :text :text "fourth question"}]}
                  {:role :assistant :sequence-id "8" :content [{:type :text :text "fourth answer"}]}]]
    (testing "index lists the rows with ids"
      (let [r (compact/run-compact-in-place {:messages messages} {:action "index"})
            text (-> r :content first :text)]
        (is (str/includes? text "first question"))
        (is (str/includes? text "3"))))

    (testing "compact returns a directive the loop can apply"
      (let [r (compact/run-compact-in-place {:messages messages}
                                            {:action "compact"
                                             :collapse [{:from "1" :to "2" :note "they said hello"}]})]
        (is (some? (:directive r)))
        (is (= 1 (count (get-in r [:directive :compact :collapse]))))))

    (testing "an unknown row id is rejected"
      (let [r (compact/run-compact-in-place {:messages messages}
                                            {:action "compact"
                                             :collapse [{:from "99" :to "100" :note "nope"}]})]
        (is (true? (:is-error r)))))

    (testing "without history it says so instead of pretending"
      (is (true? (:is-error (compact/run-compact-in-place {} {:action "index"})))))

    (testing "trim shortens tool output to a marker"
      (let [big (apply str (repeat 500 "x"))
            msgs (vec (for [i (range 1 9)]
                        {:role (if (even? i) :assistant :tool)
                         :sequence-id (str i)
                         :content [{:type :text :text (str "row " i " " big)}]}))
            r (compact/run-compact-in-place {:messages msgs} {:action "compact" :trim ["1"]})]
        (is (some? (:directive r)))
        (is (= 1 (count (get-in r [:directive :compact :trim]))))))))

;; ---------------------------------------------------------------------------
;; message_user

(deftest message-user
  (let [events (atom [])
        dir (temp-dir "msguser")
        c (ctx dir :emit #(swap! events conj %))]
    (testing "text is emitted for the UI and acknowledged to the model"
      (let [r (message-user/run-message-user c {:text "working on it"})]
        (is (= "working on it" (-> @events last :text)))
        (is (= :message-user (-> @events last :type)))
        (is (str/includes? (-> r :content first :text) "Sent"))))

    (testing "an attachment is resolved and reported"
      (spit (io/file dir "note.txt") "hi")
      (let [r (message-user/run-message-user c {:text "see below" :attachments ["note.txt"]})]
        (is (str/includes? (-> r :content first :text) "note.txt"))
        (is (= 1 (count (-> @events last :attachments))))))

    (testing "a reaction needs a target and one emoji"
      (is (thrown? clojure.lang.ExceptionInfo
                   (message-user/run-message-user c {:reaction "thumbsup"})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (message-user/run-message-user c {:reaction "thumbsup" :reply_to 3})))
      (message-user/run-message-user c {:reaction "\uD83D\uDC4D" :reply_to 3})
      (is (= "\uD83D\uDC4D" (:reaction (last @events)))))

    (testing "a question blocks until the server answers it"
      (let [answer (future (Thread/sleep 200)
                           (let [id (first (message-user/pending-question-ids))]
                             (message-user/answer! id "yes please")))
            r (message-user/run-message-user c {:question "Shall I continue?" :timeout_ms 5000})]
        @answer
        (is (str/includes? (-> r :content first :text) "yes please"))
        (is (some #(= :message-user-question (:type %)) @events) "the question was emitted")
        (is (= :message-user-question-answered (-> @events last :type))))

    (testing "nothing to send is an error"
      (is (thrown? clojure.lang.ExceptionInfo (message-user/run-message-user c {}))))

    (testing "a missing attachment is rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (message-user/run-message-user c {:text "x" :attachments ["nope.txt"]})))))))

;; ---------------------------------------------------------------------------
;; browser (only needs a browser for the last group)

(deftest browser-tool-shape
  (let [d (browse/browser-tool-def)]
    (is (= "browser" (:name d)))
    (is (fn? (:handler d)))
    (is (= "object" (get-in d [:input-schema :type])))
    (is (contains? (set (get-in d [:input-schema :properties :action :enum])) "navigate"))
    (is (= ["action"] (get-in d [:input-schema :required])))))

(deftest browser-degraded-mode
  (testing "an unknown action is an error result, not an exception"
    (is (true? (:is-error (browse/run-browser {:action "not-a-real-action"})))))
  (testing "a missing action is reported"
    (is (str/includes? (-> (browse/run-browser {}) :content first :text) "action"))))

(deftest ^:browser browser-live
  (if (nil? (cdp/find-chrome))
    (println "skipping browser live test: no Chrome installed")
    (try
      (testing "navigate, eval and screenshot work against a real page"
        (let [nav (browse/run-browser {:action "navigate"
                                       :url "data:text/html,<title>T</title><h1 id=x>Hello</h1>"})
              ev (browse/run-browser {:action "eval" :expression "document.getElementById('x').textContent"})
              shot (browse/run-browser {:action "screenshot"})]
          (is (not (:is-error nav)))
          (is (str/includes? (-> nav :content first :text) "Title: T"))
          (is (= "Hello" (-> ev :content first :text)))
          (is (= [:text :image] (mapv :type (:content shot))))))
      (finally (browse/close!)))))
