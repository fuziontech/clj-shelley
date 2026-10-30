(ns shelley.llm.gemini
  "Google Gemini generateContent adapter.

  Gemini differs from both other providers: the conversation is `contents` with
  `parts`, the system prompt is its own field, and tool calls arrive whole
  rather than as streamed fragments."
  (:require [shelley.llm :as llm]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- block->part [block]
  (case (:type block)
    :text {:text (:text block)}
    :image {:inline_data {:mime_type (:media-type block) :data (:data block)}}
    :tool-use {:functionCall {:name (:name block) :args (or (:input block) {})}}
    :tool-result {:functionResponse {:name (or (:name block) "tool")
                                     :response {:content (str/join "\n" (keep :text (:content block)))}}}
    nil))

(defn- message->content [{:keys [role content]}]
  {:role (if (= :assistant role) "model" "user")
   :parts (vec (keep block->part content))})

(defn- build-body [{:keys [system messages tools max-tokens temperature thinking-level]}]
  (cond-> {:contents (mapv message->content (llm/merge-consecutive messages))}
    (seq system) (assoc :systemInstruction {:parts (mapv (fn [s] {:text s}) system)})
    (seq tools) (assoc :tools [{:functionDeclarations
                                (mapv (fn [t] {:name (:name t)
                                               :description (:description t)
                                               :parameters (:input-schema t)})
                                      tools)}])
    max-tokens (assoc :generationConfig {:maxOutputTokens max-tokens})
    temperature (assoc-in [:generationConfig :temperature] temperature)
    (llm/thinking-budget thinking-level)
    (assoc-in [:generationConfig :thinkingConfig] {:thinkingBudget (llm/thinking-budget thinking-level)})))

(defn send-request
  [{:keys [model] :as request} on-event]
  (let [body (build-body (assoc request :tools (mapv llm/->api-tool (:tools request))))
        url (str (or (:base-url request) (llm/base-url)) "/v1beta/models/"
                 (str/replace model #"^.*/" "") ":streamGenerateContent?alt=sse")
        headers (cond-> {} (:api-key request) (assoc "x-goog-api-key" (:api-key request)))
        text (StringBuilder.)
        calls (atom [])
        usage (atom {})
        stop (atom nil)]
    (doseq [chunk (llm/stream-json url headers body)]
      (when-let [u (:usageMetadata chunk)]
        (reset! usage {:input-tokens (:promptTokenCount u)
                       :output-tokens (:candidatesTokenCount u)}))
      (doseq [candidate (:candidates chunk)]
        (doseq [part (get-in candidate [:content :parts])]
          (when-let [t (:text part)]
            (.append text t)
            (when on-event (on-event {:type :text-delta :text t})))
          (when-let [fc (:functionCall part)]
            (let [id (str "gem-" (count @calls))]
              (swap! calls conj {:type :tool-use :id id
                                 :name (:name fc) :input (or (:args fc) {})})
              (when on-event (on-event {:type :tool-use-start :id id :name (:name fc)})))))
        (when-let [r (get-in candidate [:finishReason])]
          (reset! stop r))))
    (let [content (cond-> []
                    (pos? (.length text)) (conj (llm/text-block (str text)))
                    (seq @calls) (into @calls))]
      {:content content
       :stop-reason (if (seq @calls) :tool-use (if (= "MAX_TOKENS" @stop) :max-tokens :end-turn))
       :model model
       :usage @usage})))
