(ns shelley.loop-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [shelley.llm :as llm]
            [shelley.llm.fixture :as fixture]
            [shelley.loop :as loop]
            [shelley.compact :as compact]
            [shelley.tools :as tools]))

(use-fixtures :each (fn [t] (fixture/reset-scripts!) (t)))

(defn- run [opts]
  (loop/run-turn (merge {:model "predictable/test"
                         :system ["sys"]
                         :messages [(llm/user-message "go")]
                         :tools []
                         :ctx {:working-dir (atom "/tmp")}}
                        opts)))

(deftest fixture-provider-streams-and-stops
  (fixture/register! "predictable/test"
                     [{:content [(llm/text-block "hello")] :stop-reason :end-turn
                       :usage {:input-tokens 5 :output-tokens 2}}])
  (let [events (atom [])
        result (run {:emit (fn [e] (swap! events conj e))})]
    (is (= :complete (:status result)))
    (is (= "hello" (llm/text-of (last (:messages result)))))
    (is (some #(= {:type :text-delta :text "hello"} %) @events))
    (is (= 5 (get-in result [:usage :input-tokens])))))

(deftest queued-user-messages-are-spliced-in-mid-turn
  (let [queue (atom [])
        seen (atom [])
        responses (atom [{:content [{:type :tool-use :id "c1" :name "bash"
                                     :input {:command "true"}}]
                          :stop-reason :tool-use}
                         {:content [(llm/text-block "done")] :stop-reason :end-turn}])]
    (with-redefs [llm/complete (fn [req]
                                 (swap! seen conj (:messages req))
                                 (let [r (first @responses)]
                                   (swap! responses rest)
                                   ;; the user types this while the tool is running
                                   (when (= 1 (count @seen)) (reset! queue ["also do this"]))
                                   r))]
      (run {:queue queue :tools (tools/tool-defs nil)})
      (testing "the second request contains the queued message"
        (is (some #(some-> % llm/text-of (clojure.string/includes? "also do this"))
                  (second @seen))))
      (testing "the queue is drained"
        (is (empty? @queue))))))

(deftest injected-messages-appear-before-the-next-request
  (let [seen (atom [])
        pending (atom [{:role :user :content [(llm/text-block "subagent finished")]}])
        responses (atom [{:content [{:type :tool-use :id "c1" :name "bash" :input {:command "true"}}]
                          :stop-reason :tool-use}
                         {:content [(llm/text-block "ok")] :stop-reason :end-turn}])]
    (with-redefs [llm/complete (fn [req]
                                 (swap! seen conj (:messages req))
                                 (let [r (first @responses)] (swap! responses rest) r))]
      (run {:tools (tools/tool-defs nil)
            :inject (fn [] (let [msgs @pending] (reset! pending []) msgs))})
      (is (some #(some-> % llm/text-of (clojure.string/includes? "subagent finished"))
                (second @seen))))))

(deftest compaction-fires-over-the-limit
  (let [big (apply str (repeat 5000 "x"))
        messages (vec (cons (llm/user-message "start")
                            (for [i (range 30)]
                              (llm/assistant-message [(llm/text-block (str i " " big))]))))
        compacted (atom nil)]
    (with-redefs [llm/complete (fn [_] {:content [(llm/text-block "summary")] :stop-reason :end-turn})]
      (run {:messages messages
            :context-limit 1000
            :compact-fn (fn [msgs]
                          (let [out (compact/compact-history msgs {:model "m"})]
                            (reset! compacted out)
                            out))}))
    (is (some? @compacted) "compaction ran")
    (is (< (count @compacted) (count messages)) "history got shorter")))

(deftest failed-request-is-recorded-and-reported
  (let [errors (atom [])]
    (with-redefs [llm/complete (fn [_] (throw (ex-info "boom" {:status 500})))]
      (let [result (run {:record-error! (fn [m] (swap! errors conj m))})]
        (is (= :error (:status result)))
        (is (re-find #"boom" (:error result)))
        (is (= 1 (count @errors)))
        (is (= :llm-request (get-in (first @errors) [:error :type])))))))

(deftest compaction-keeps-tool-pairs-together
  (let [messages [{:role :user :content [(llm/text-block "a")]}
                  {:role :assistant :content [{:type :tool-use :id "1" :name "bash" :input {}}]}
                  {:role :user :content [{:type :tool-result :tool-use-id "1"
                                          :content [(llm/text-block "r")]}]}
                  {:role :assistant :content [(llm/text-block "b")]}
                  {:role :user :content [(llm/text-block "c")]}]
        out (with-redefs [llm/complete (fn [_] {:content [(llm/text-block "sum")] :stop-reason :end-turn})]
              (compact/compact-history messages {:model "m"} 2))]
    (testing "the result starts with the summary"
      (is (= :user (:role (first out))))
      (is (re-find #"Summary" (llm/text-of (first out)))))
    (testing "no tool-result is left without its tool-use in the retained tail"
      (let [tail (rest out)
            use-ids (set (map :id (mapcat llm/tool-uses tail)))
            result-ids (set (keep #(when (llm/tool-result? %) (:tool-use-id %))
                                  (mapcat :content tail)))]
        (is (every? use-ids result-ids))))))
