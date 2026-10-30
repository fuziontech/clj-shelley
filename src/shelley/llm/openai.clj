(ns shelley.llm.openai
  "OpenAI chat-completions wire adapter (also used for every non-Claude model
  behind the exe.dev gateway).

  Two shapes differ from the internal model and are reconciled here:
    * tool results are their own `role: tool` messages, not content blocks, so
      one internal message can expand into several wire messages;
    * tool-call arguments arrive as a JSON *string*, streamed in fragments
      keyed by index."
  (:require [shelley.llm :as llm]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- content->wire [block]
  (case (:type block)
    :text {:type "text" :text (:text block)}
    :image {:type "image_url"
            :image_url {:url (str "data:" (:media-type block) ";base64," (:data block))}}
    nil))

(defn- tool-result-text [block]
  (str/join "\n" (keep :text (:content block))))

(defn- message->wire
  "Expand one internal message into the wire messages it requires."
  [{:keys [role content]}]
  (let [results (filter llm/tool-result? content)
        others (remove llm/tool-result? content)
        tool-calls (keep (fn [b]
                           (when (llm/tool-use? b)
                             {:id (:id b)
                              :type "function"
                              :function {:name (:name b)
                                         :arguments (json/generate-string (or (:input b) {}))}}))
                         others)
        body (cond-> {:role (name role)}
               (seq tool-calls) (assoc :tool_calls (vec tool-calls))
               (seq others) (assoc :content (vec (keep content->wire others))))]
    (concat
     ;; A pure tool-result message carries no assistant text of its own.
     (when (or (seq (:content body)) (:tool_calls body)) [body])
     (map (fn [r] {:role "tool"
                   :tool_call_id (:tool-use-id r)
                   :content (tool-result-text r)})
          results))))

(defn- build-body [{:keys [model system messages tools max-tokens temperature thinking-level]}]
  (cond-> {:model model
           :stream true
           :stream_options {:include_usage true}
           :messages (vec (concat (map (fn [s] {:role "system" :content s}) system)
                                  (mapcat message->wire messages)))}
    (seq tools) (assoc :tools (mapv (fn [t]
                                      {:type "function"
                                       :function {:name (:name t)
                                                  :description (:description t)
                                                  :parameters (:input-schema t)}})
                                    tools))
    max-tokens (assoc :max_completion_tokens max-tokens)
    temperature (assoc :temperature temperature)
    ;; No reasoning_effort here on purpose: the gateway rejects every value it
    ;; offers when tools are present. Reasoning models are routed to
    ;; /v1/responses by shelley.llm/send-request before they reach this code.
    ))

(defn- accumulate-tool-call [state {:keys [index id function]}]
  (let [index (or index 0)
        slot (get state index {:id nil :name nil :args ""})]
    (assoc state index
           {:id (or id (:id slot))
            :name (or (not-empty (:name function)) (:name slot))
            :args (str (:args slot) (or (:arguments function) ""))})))

(defn- finish-reason->stop [reason has-tools?]
  (cond
    has-tools? :tool-use
    (= reason "length") :max-tokens
    :else :end-turn))

(defn send-request
  "Stream one request. Returns {:content [block] :stop-reason kw :usage map}."
  [request on-event]
  (let [{:keys [model] :as request} request
        on-event (or on-event (:on-event request))
        body (build-body (assoc request :tools (mapv llm/->api-tool (:tools request))))
        url (str (or (:base-url request) (llm/base-url)) "/v1/chat/completions")
        headers (cond-> {}
                  (:api-key request) (assoc "authorization" (str "Bearer " (:api-key request))))
        acc (atom {:text "" :thinking "" :tool-calls {} :usage nil :finish nil})
        text-buf (StringBuilder.)
        think-buf (StringBuilder.)]
    (doseq [chunk (llm/stream-json url headers body)]
      (when-let [usage (:usage chunk)] (swap! acc assoc :usage usage))
      (doseq [choice (:choices chunk)]
        (when-let [delta (:delta choice)]
          (when-let [t (:content delta)]
            (when-not (str/blank? t)
              (.append text-buf t)
              (when on-event (on-event {:type :text-delta :text t}))))
          (when-let [rt (or (:reasoning_content delta) (:reasoning delta))]
            (.append think-buf rt)
            (when on-event (on-event {:type :thinking-delta :text rt})))
          (when-let [calls (:tool_calls delta)]
            (doseq [call calls]
              (when (:id call)
                (when on-event
                  (on-event {:type :tool-use-start :id (:id call) :name (get-in call [:function :name])})))
              (swap! acc update :tool-calls accumulate-tool-call call))))
        (when-let [reason (:finish_reason choice)]
          (swap! acc assoc :finish reason))))
    (let [{:keys [tool-calls finish usage]} @acc
          calls (sort-by key tool-calls)
          blocks (cond-> []
                   (pos? (.length think-buf)) (conj {:type :thinking :thinking (str think-buf)})
                   (pos? (.length text-buf)) (conj (llm/text-block (str text-buf)))
                   (seq calls) (into (map (fn [[_ {:keys [id name args]}]]
                                            {:type :tool-use
                                             :id id
                                             :name name
                                             :input (if (str/blank? args)
                                                      {}
                                                      (try (json/parse-string args true)
                                                           (catch Exception _ {})))})
                                          calls)))]
      {:content blocks
       :stop-reason (finish-reason->stop finish (boolean (seq calls)))
       :model model
       :usage (when usage {:input-tokens (:prompt_tokens usage)
                           :output-tokens (:completion_tokens usage)
                           :cache-read-tokens (get-in usage [:prompt_tokens_details :cached_tokens])})})))
