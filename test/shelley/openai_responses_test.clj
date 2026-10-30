(ns shelley.openai-responses-test
  "The Responses API is a different wire format from chat completions, and the
  differences are the kind that fail silently: a wrong content-part type or a
  tool result attached to the wrong item produces a 400 naming a field, or worse,
  a model that never sees its own tool results. These tests pin the shape."
  (:require [clojure.test :refer [deftest is testing]]
            [shelley.llm.openai-responses :as r]))

(def build-body #'r/build-body)

(def ^:private bash-tool
  {:name "bash" :description "Run a command" :input-schema {:type "object"}})

(deftest reasoning-models-are-recognised
  (is (r/reasoning-model? "openai/gpt-6.1-sol"))
  (is (r/reasoning-model? "gpt-5.1"))
  (is (r/reasoning-model? "o3-mini"))
  (is (not (r/reasoning-model? "anthropic/claude-sonnet-5-5")))
  (is (not (r/reasoning-model? "openai/gpt-4.1"))))

(deftest body-carries-instructions-not-a-system-message
  (let [body (build-body {:model "openai/gpt-6.1-sol"
                          :system ["be terse" "you are an agent"]
                          :messages [{:role :user :content [{:type :text :text "hi"}]}]})]
    (is (= "be terse\n\nyou are an agent" (:instructions body)))
    (is (= [{:role "user" :content [{:type "input_text" :text "hi"}]}] (:input body)))
    (is (true? (:stream body)))
    (testing "store is off: a server-side conversation we cannot read is a leak"
      (is (false? (:store body))))))

(deftest assistant-text-uses-output-text
  (let [body (build-body {:model "m"
                          :messages [{:role :agent :content [{:type :text :text "answer"}]}]})]
    (is (= [{:type "output_text" :text "answer"}] (get-in body [:input 0 :content])))
    (is (= "assistant" (get-in body [:input 0 :role])))))

(deftest tool-use-and-results-become-items
  (let [body (build-body
              {:model "m"
               :messages [{:role :agent :content [{:type :tool-use :id "call-1" :name "bash"
                                                   :input {:command "ls"}}]}
                          {:role :user :content [{:type :tool-result :tool-use-id "call-1"
                                                  :content [{:type :text :text "a.txt"}]}]}]})
        items (:input body)]
    (is (= "function_call" (:type (first items))))
    (is (= "call-1" (:call_id (first items))))
    (is (= {:command "ls"} (cheshire.core/parse-string (:arguments (first items)) true)))
    (is (= "function_call_output" (:type (second items))))
    (is (= "a.txt" (:output (second items))))))

(deftest tools-are-flat-not-nested-under-function
  (let [body (build-body {:model "m" :tools [bash-tool]
                          :messages [{:role :user :content [{:type :text :text "hi"}]}]})]
    (is (= [{:type "function" :name "bash" :description "Run a command"
             :parameters {:type "object"}}] (:tools body)))))

(deftest thinking-level-becomes-reasoning-effort
  (testing "off omits the field entirely — the models reject \"none\""
    (is (nil? (:reasoning (build-body {:model "m" :thinking-level :off :messages []})))))
  (is (= {:effort "medium"} (:reasoning (build-body {:model "m" :thinking-level :medium :messages []}))))
  (is (= {:effort "high"} (:reasoning (build-body {:model "m" :thinking-level :max :messages []})))))

(deftest images-ride-along-as-input-image
  (let [body (build-body {:model "m"
                          :messages [{:role :user :content [{:type :image :media-type "image/png"
                                                             :data "AAAA"}]}]})]
    (is (= [{:type "input_image" :image_url "data:image/png;base64,AAAA"}]
           (get-in body [:input 0 :content])))))
