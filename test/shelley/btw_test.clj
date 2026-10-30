(ns shelley.btw-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shelley.btw :as btw]))

(def msgs
  [{:type :user :content [{:type :text :text "Add a cache to the loader"}]}
   {:type :agent :content [{:type :text :text "I will add an LRU."}
                           {:type :tool-use :name "bash" :input {}}]}
   {:type :user :content [{:type :tool-result :name "bash" :is-error false
                           :content [{:type :text :text "ok"}]}]}
   {:type :agent :content [{:type :text :text "Done, tests pass."}]}])

(deftest frozen-reference-renders-a-transcript
  (let [ref (btw/frozen-reference msgs)]
    (testing "it is delimited so the model can tell it from live conversation"
      (is (str/starts-with? ref btw/reference-begin))
      (is (str/includes? ref btw/reference-end)))
    (testing "speakers are labelled"
      (is (str/includes? ref "USER: Add a cache"))
      (is (str/includes? ref "AGENT: I will add an LRU.")))
    (testing "tool calls are summarised rather than dumped"
      (is (str/includes? ref "(called bash)")))
    (testing "tool results do not dominate the transcript"
      (is (not (str/includes? ref "result of bash"))))))

(deftest reference-is-capped
  (let [many (vec (for [i (range 50)]
                    {:type :user :content [{:type :text :text (str "m" i)}]}))
        ref (btw/frozen-reference many 10)]
    (is (str/includes? ref "earlier messages omitted"))
    (is (str/includes? ref "m49"))
    (is (not (str/includes? ref "m0\n")))))

(deftest system-prompt-forbids-continuing-the-work
  (let [p (btw/system-prompt "REF")]
    (is (str/includes? p "Do not continue the session's work"))
    (is (str/includes? p "REF"))))

(deftest options-mark-the-kind-and-parent
  (is (= {:kind "btw" :parent_pointer {:conversation_id "c1" :message_id "m7"}}
         (btw/options "c1" "m7")))
  (let [conv {:options (btw/options "c1" "m7")}]
    (is (btw/btw? conv))
    (is (= "c1" (btw/parent-id conv)))
    (is (btw/active? conv))
    (is (not (btw/active? (assoc conv :options (btw/dismiss-options conv)))))
    (is (not (btw/btw? {:options {:kind "search"}})))))

(deftest summarize-prompt-asks-for-brevity
  (let [p (btw/summarize-prompt "Why LRU?" "Because the loader is hot.")]
    (is (str/includes? p "at most two sentences"))
    (is (str/includes? p "Why LRU?"))))
