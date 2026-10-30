(ns shelley.llm.openai-responses
  "OpenAI's Responses API (`/v1/responses`).

  Why this exists next to the chat-completions adapter: the reasoning models
  (gpt-5, gpt-6, o-series) refuse function tools on `/v1/chat/completions` — the
  gateway answers `unsupported_value` if you disable reasoning and
  `invalid_request_error` if you do not. `/v1/responses` is the endpoint that
  lets such a model both think and call tools, so an agent that is pointed at one
  of those models must come through here.

  The wire format differs from chat completions in three ways that matter:

  * input is a flat list of *items* — messages, `function_call` items and
    `function_call_output` items — rather than roles carrying tool calls;
  * the system prompt is `instructions`, not a message;
  * streaming emits typed events (`response.output_text.delta`,
    `response.function_call_arguments.delta`) rather than deltas on a choice.

  Everything is normalised back into this project's block model before it
  leaves the namespace, so the loop never learns which API was used."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [shelley.llm :as llm]))

(defn reasoning-model? [model]
  (boolean (re-find #"(gpt-5|gpt-6|gpt-7|o[1-9]|codex)" (str model))))

(defn- effort
  "Map our thinking levels onto the API's. `off` becomes omission rather than
  \"none\", which the models reject."
  [level]
  (case level
    (:off nil) nil
    :minimal "minimal"
    :low "low"
    :medium "medium"
    :high "high"
    (:xhigh :max) "high"
    nil))

(defn- ->input
  "Convert our message vector into Responses input items."
  [messages]
  (vec
   (mapcat
    (fn [{:keys [role content]}]
      (let [texts (filter llm/text? content)
            tools (filter llm/tool-use? content)
            results (filter #(= :tool-result (:type %)) content)
            images (filter #(= :image (:type %)) content)]
        (concat
         ;; A message item carries text; a tool call or result is its own item.
         (when (seq texts)
           [{:role (if (= role :agent) "assistant" role)
             :content (vec (concat
                            (map (fn [b] {:type (if (= role :agent) "output_text" "input_text")
                                          :text (:text b)}) texts)
                            (map (fn [b] {:type "input_image"
                                          :image_url (str "data:" (:media-type b)
                                                          ";base64," (:data b))}) images)))}])
         (map (fn [b] {:type "function_call"
                       :call_id (:id b)
                       :name (:name b)
                       :arguments (json/generate-string (or (:input b) {}))}) tools)
         (map (fn [b] {:type "function_call_output"
                       :call_id (:tool-use-id b)
                       :output (str/join "\n" (keep :text (:content b)))}) results))))
    messages)))

(defn- build-body [{:keys [model system messages tools max-tokens temperature
                           thinking-level prompt-caching]}]
  (cond-> {:model model
           :input (->input messages)
           :stream true
           :store false}
    (seq system) (assoc :instructions (str/join "\n\n" system))
    (effort thinking-level) (assoc :reasoning {:effort (effort thinking-level)})
    (seq tools) (assoc :tools (mapv (fn [t] {:type "function"
                                             :name (:name t)
                                             :description (:description t)
                                             :parameters (:input-schema t)})
                                    tools))
    max-tokens (assoc :max_output_tokens max-tokens)
    temperature (assoc :temperature temperature)
    prompt-caching (assoc :prompt_cache_key "clj-shelley")))

(defn- stop->reason [status incomplete]
  (cond
    (= "incomplete" status)
    (if (= "max_output_tokens" (:reason incomplete)) :max-tokens :end-turn)
    :else :end-turn))

(defn send-request
  "Stream one request. Returns {:content [block] :stop-reason kw :usage map}."
  [request on-event]
  (let [{:keys [model] :as request} request
        on-event (or on-event (:on-event request))
        body (build-body request)
        url (str (or (:base-url request) (llm/base-url)) "/v1/responses")
        ;; post-stream sets content-type; adding it here duplicates the header
        ;; and the gateway rejects "application/json, application/json".
        headers (cond-> {}
                  (:api-key request) (assoc "authorization" (str "Bearer " (:api-key request))))
        text (atom "")
        thinking (atom "")
        calls (atom {})          ; output item id -> {:call-id :name :args}
        call-order (atom [])
        usage (atom {})
        status (atom nil)
        incomplete (atom nil)]
    (doseq [event (llm/stream-json url headers body)]
      (case (:type event)
        "response.output_text.delta"
        (do (swap! text str (:delta event))
            (when on-event (on-event {:type :text-delta :text (:delta event)})))

        ("response.reasoning_summary_text.delta" "response.reasoning_text.delta")
        (do (swap! thinking str (:delta event))
            (when on-event (on-event {:type :thinking-delta :text (:delta event)})))

        "response.output_item.added"
        (let [item (:item event)]
          (when (= "function_call" (:type item))
            (swap! calls assoc (:id item) {:call-id (:call_id item)
                                           :name (:name item)
                                           :args ""})
            (swap! call-order conj (:id item))
            (when on-event
              (on-event {:type :tool-use-start :id (:call_id item) :name (:name item)}))))

        "response.function_call_arguments.delta"
        (let [id (:item_id event)]
          (swap! calls update-in [id :args] str (:delta event))
          (when on-event
            (on-event {:type :tool-args-delta :id id :text (:delta event)})))

        "response.completed"
        (let [r (:response event)]
          (reset! status (:status r))
          (reset! incomplete (:incomplete_details r))
          (reset! usage (:usage r)))

        "response.failed"
        (let [err (get-in event [:response :error])]
          (throw (ex-info (str "LLM request failed: " (or (:message err) "unknown error"))
                          {:status (:code err) :body err})))

        nil))
    (let [tool-blocks (mapv (fn [id]
                              (let [{:keys [call-id name args]} (get @calls id)]
                                {:type :tool-use
                                 :id call-id
                                 :name name
                                 :input (try (if (str/blank? args)
                                               {}
                                               (json/parse-string args true))
                                             (catch Exception _ {}))}))
                            @call-order)
          content (cond-> []
                    (not (str/blank? @thinking)) (conj {:type :thinking :thinking @thinking})
                    (not (str/blank? @text)) (conj {:type :text :text @text})
                    (seq tool-blocks) (into tool-blocks))
          u @usage]
      {:content (vec content)
       :stop-reason (if (seq tool-blocks) :tool-use (stop->reason @status @incomplete))
       :model model
       :usage {:input-tokens (:input_tokens u)
               :output-tokens (:output_tokens u)
               :cache-read-tokens (get-in u [:input_tokens_details :cached_tokens])
               :reasoning-tokens (get-in u [:output_tokens_details :reasoning_tokens])}})))
