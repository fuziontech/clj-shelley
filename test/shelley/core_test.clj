(ns shelley.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [shelley.llm :as llm]
            [shelley.llm.openai :as openai]
            [shelley.loop :as loop]
            [shelley.tools :as tools]
            [shelley.server :as server]
            [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Serialisation: the bug that made every persisted message send as empty.

(deftest block-round-trip
  (testing "keyword :type survives a JSON round trip"
    (let [block {:type :tool-use :id "c1" :name "bash" :input {"command" "ls"}}
          back (llm/normalize-block (json/parse-string (json/generate-string block) true))]
      (is (= :tool-use (:type back)))
      (is (= "bash" (:name back)))))

  (testing "nested tool-result content is normalised too"
    (let [block {:type :tool-result :tool-use-id "c1" :is-error false
                 :content [{:type :text :text "hi"}]}
          back (llm/normalize-block (json/parse-string (json/generate-string block) true))]
      (is (= :text (-> back :content first :type))))))

(deftest role-merge
  (testing "consecutive same-role messages are merged for strict-alternation providers"
    (let [merged (llm/merge-consecutive [{:role :user :content [(llm/text-block "a")]}
                                         {:role :user :content [(llm/text-block "b")]}
                                         {:role :assistant :content [(llm/text-block "c")]}])]
      (is (= 2 (count merged)))
      (is (= "ab" (apply str (map :text (:content (first merged)))))))))

(deftest provider-selection
  (is (= :anthropic (llm/provider-for "anthropic/claude-sonnet-5-5")))
  (is (= :openai (llm/provider-for "openai/gpt-5.5"))))

;; ---------------------------------------------------------------------------
;; OpenAI wire translation

(deftest openai-wire-shape
  (testing "tool results become role:tool messages, not content blocks"
    (let [msgs [{:role :assistant
                 :content [{:type :tool-use :id "c1" :name "bash" :input {"command" "ls"}}]}
                {:role :user
                 :content [{:type :tool-result :tool-use-id "c1" :is-error false
                            :content [(llm/text-block "out")]}]}]
          wire (mapcat #'openai/message->wire msgs)]
      (is (= ["assistant" "tool"] (map :role wire)))
      (is (= "c1" (:tool_call_id (second wire))))
      (is (= "{\"command\":\"ls\"}" (get-in (first wire) [:tool_calls 0 :function :arguments]))))))

;; ---------------------------------------------------------------------------
;; Tools

(defn- temp-file [content]
  (let [f (java.io.File/createTempFile "clj-shelley" ".txt")]
    (spit f content)
    f))

(deftest patch-semantics
  (let [f (temp-file "alpha beta gamma")
        ctx {:working-dir (atom (.getParent f))}]
    (testing "unique replace works"
      (tools/run-patch ctx {:operations [{:path (.getName f) :operation "replace"
                                          :oldText "beta" :newText "BETA"}]})
      (is (= "alpha BETA gamma" (slurp f))))

    (testing "ambiguous replace is refused rather than guessing"
      (spit f "x x")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"more than once"
                            (tools/run-patch ctx {:operations [{:path (.getName f)
                                                                :operation "replace"
                                                                :oldText "x" :newText "y"}]}))))

    (testing "missing oldText is reported"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found"
                            (tools/run-patch ctx {:operations [{:path (.getName f)
                                                                :operation "replace"
                                                                :oldText "zzz" :newText "y"}]}))))

    (testing "append keeps a trailing newline"
      (spit f "line1")
      (tools/run-patch ctx {:operations [{:path (.getName f) :operation "append_eof"
                                          :newText "line2"}]})
      (is (= "line1\nline2" (slurp f)))    )))

(deftest bash-and-change-dir
  (let [dir (str (System/getProperty "java.io.tmpdir"))
        ctx {:working-dir (atom dir)}]
    (testing "bash runs in the working directory"
      (let [res (tools/run-bash ctx {:command "pwd"})]
        (is (= dir (clojure.string/trim (-> res :content first :text))))))
    (testing "change_dir moves subsequent commands"
      (tools/run-change-dir ctx {:path "/tmp"})
      (is (= "/tmp" (clojure.string/trim (-> (tools/run-bash ctx {:command "pwd"})
                                             :content first :text)))))
    (testing "a failing command is marked as an error"
      (let [res (tools/run-bash ctx {:command "exit 3"})]
        (is (:is-error res))))))

(deftest unknown-tool-is-a-tool-result-not-a-crash
  (let [r (tools/execute {:working-dir (atom "/tmp")} {:id "x" :name "nope" :input {}})]
    (is (= :tool-result (:type r)))
    (is (:is-error r))))

;; ---------------------------------------------------------------------------
;; The loop, against a scripted model

(defn- scripted-llm
  "Returns a function usable as llm/complete that plays back `responses`."
  [responses calls]
  (fn [request]
    (swap! calls conj request)
    (let [r (first @responses)]
      (swap! responses rest)
      (if r
        r
        {:content [(llm/text-block "done")] :stop-reason :end-turn :usage {}}))))

(deftest loop-executes-tools-then-stops
  (let [responses (atom [{:content [{:type :tool-use :id "c1" :name "bash"
                                     :input {"command" "echo hi"}}]
                          :stop-reason :tool-use :usage {}}
                         {:content [(llm/text-block "finished")] :stop-reason :end-turn :usage {}}])
        calls (atom [])
        recorded (atom [])
        events (atom [])
        result (with-redefs [llm/complete (scripted-llm responses calls)]
                 (loop/run-turn {:model "test-model"
                                 :system ["sys"]
                                 :messages [(llm/user-message "go")]
                                 :tools (tools/tool-defs nil)
                                 :ctx {:working-dir (atom "/tmp")}
                                 :emit (fn [e] (swap! events conj e))
                                 :record! (fn [m] (swap! recorded conj m))}))]
    (is (= :complete (:status result)))
    (testing "two model calls: one with tools, one to finish"
      (is (= 2 (count @calls))))
    (testing "the tool actually ran"
      (let [tool-msg (nth (:messages result) 2)]
        (is (= :user (:role tool-msg)))
        (is (= "hi" (clojure.string/trim (-> tool-msg :content first :content first :text))))))
    (testing "assistant, tool-result and final assistant messages were persisted"
      (is (= [:assistant :user :assistant] (mapv :role @recorded))))
    (testing "the UI saw a start and an end event"
      (is (some #(= :tool-start (:type %)) @events))
      (is (some #(= :tool-end (:type %)) @events)))
    (testing "the second request carried the tool result back to the model"
      (let [second-req (second @calls)]
        (is (some #(= :tool-result (:type %))
                  (mapcat :content (:messages second-req))))))))

(deftest loop-stops-when-cancelled
  (let [responses (atom [{:content [{:type :tool-use :id "c1" :name "bash"
                                     :input {"command" "echo hi"}}]
                          :stop-reason :tool-use :usage {}}])
        result (with-redefs [llm/complete (scripted-llm responses (atom []))]
                 (loop/run-turn {:model "m" :system [] :messages [(llm/user-message "go")]
                                 :tools [] :ctx {:working-dir (atom "/tmp")}
                                 :cancelled? (constantly true)}))]
    (is (= :cancelled (:status result)))))

;; ---------------------------------------------------------------------------
;; Router

(deftest routing
  (testing "static and parameterised paths both match"
    (is (= [:conversations {}] (let [[_ p] (#'server/match-route :get "/api/conversations")]
                                 [:conversations p])))
    (is (= {:id "abc"} (second (#'server/match-route :get "/api/conversations/abc")))))
  (testing "wrong verb and wrong depth do not match"
    (is (nil? (#'server/match-route :delete "/api/conversations")))
    (is (nil? (#'server/match-route :get "/api/conversations/a/b")))))

;; ---------------------------------------------------------------------------
;; Per-model routes
;;
;; A model the user added must reach its own endpoint with its own key and its
;; own wire name. Getting this wrong is silent in the worst way: the request
;; leaves for the gateway, the gateway answers "no such model", and the trace
;; looks like a provider problem rather than a routing one.

(deftest a-custom-model-routes-to-its-own-endpoint
  (llm/clear-routes!)
  (try
    (llm/register-route! "acme/thing"
                         {:provider :openai
                          :base-url "https://acme.test"
                          :api-key "secret-key"
                          :wire-model "thing-v2"})
    (let [seen (atom nil)]
      (with-redefs [openai/send-request (fn [request _on-event]
                                          (reset! seen request)
                                          {:content [{:type :text :text "ok"}]
                                           :stop-reason :stop :usage {}})]
        (llm/complete {:model "acme/thing" :messages [{:role :user :content []}]}))
      (testing "the wire name is what the provider sees"
        (is (= "thing-v2" (:model @seen))))
      (testing "the endpoint and key are the model's, not the gateway's"
        (is (= "https://acme.test" (:base-url @seen)))
        (is (= "secret-key" (:api-key @seen))))
      (testing "provider dispatch follows the route, not the id"
        (is (= :openai (:model-override-provider @seen)))))
    (testing "a gateway model is untouched: no route, no rewrite"
      (is (nil? (llm/route-for "anthropic/claude-sonnet-5-5"))))
    (finally (llm/clear-routes!))))

(deftest deleting-a-route-stops-rewriting
  (llm/register-route! "acme/gone" {:base-url "https://gone.test" :wire-model "gone"})
  (llm/unregister-route! "acme/gone")
  (is (nil? (llm/route-for "acme/gone"))))
