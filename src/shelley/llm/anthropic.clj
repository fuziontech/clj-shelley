(ns shelley.llm.anthropic
  "Anthropic Messages wire adapter.

  Closer to the internal model than OpenAI: tool calls and tool results are
  content blocks, so translation is nearly structural. The one difference is
  that a tool result must sit in a *user* message."
  (:require [shelley.llm :as llm]
            [clojure.string :as str]))

(def ^:private api-version "2023-06-01")

(defn- block->wire [block]
  (case (:type block)
    :text {:type "text" :text (:text block)}
    :thinking (when-not (str/blank? (:signature block))
                ;; A thinking block without its signature is rejected, so it is
                ;; dropped rather than sent malformed.
                {:type "thinking" :thinking (:thinking block) :signature (:signature block)})
    :image {:type "image"
            :source {:type "base64"
                     :media_type (:media-type block)
                     :data (:data block)}}
    :tool-use {:type "tool_use" :id (:id block) :name (:name block)
               :input (or (:input block) {})}
    :tool-result {:type "tool_result"
                  :tool_use_id (:tool-use-id block)
                  :is_error (boolean (:is-error block))
                  :content (mapv (fn [b] (if (= :text (:type b))
                                           {:type "text" :text (:text b)}
                                           (block->wire b)))
                                 (:content block))}
    nil))

(defn- message->wire [{:keys [role content]}]
  {:role (name role)
   :content (vec (keep block->wire content))})

(defn- add-cache-control
  "Prompt caching: mark the end of the stable prefix (the last tool and the last
  user turn) so the provider can reuse everything before it. This is the single
  largest cost lever on a long agent conversation."
  [body]
  (let [msgs (:messages body)
        last-user (last (keep-indexed (fn [i m] (when (= "user" (:role m)) i)) msgs))]
    (cond-> body
      (seq (:tools body))
      (assoc :tools (update (:tools body) (dec (count (:tools body)))
                            #(assoc % :cache_control {:type "ephemeral"})))

      (some? last-user)
      (assoc :messages
             (update msgs last-user
                     (fn [m]
                       (if (seq (:content m))
                         (update m :content (fn [c] (update c (dec (count c))
                                                            #(assoc % :cache_control {:type "ephemeral"}))))
                         m)))))))

(defn- build-body [{:keys [model system messages tools max-tokens temperature
                           thinking-level prompt-caching]}]
  (cond-> {:model model
           :max_tokens (or max-tokens 32000)
           :stream true
           :messages (mapv message->wire messages)}
    (seq system) (assoc :system (mapv (fn [s] {:type "text" :text s}) system))
    (seq tools) (assoc :tools (mapv (fn [t] {:name (:name t)
                                            :description (:description t)
                                            :input_schema (:input-schema t)})
                                    tools))
    (and temperature (not (llm/thinking-budget thinking-level))) (assoc :temperature temperature)
    (llm/thinking-budget thinking-level)
    (assoc :thinking {:type "enabled" :budget_tokens (llm/thinking-budget thinking-level)})))

(def ^:private default-max-tokens 32000)

(defn- stop->reason [stop]
  (case stop
    "tool_use" :tool-use
    "max_tokens" :max-tokens
    "refusal" :refusal
    :end-turn))

(defn send-request
  [request on-event]
  (let [{:keys [model] :as request} request
        on-event (or on-event (:on-event request))
        body (build-body (assoc request :tools (mapv llm/->api-tool (:tools request))))
        url (str (or (:base-url request) (llm/base-url)) "/v1/messages")
        headers {"x-api-key" (or (:api-key request) "implicit")
                 "anthropic-version" api-version}
        blocks (atom {})
        order (atom [])
        usage (atom {})
        stop (atom nil)]
    (doseq [event (llm/stream-json url headers body)]
      (case (:type event)
        "message_start"
        (swap! usage merge (select-keys (get-in event [:message :usage]) [:input_tokens]))

        "content_block_start"
        (let [{:keys [index content_block]} event]
          (swap! order conj index)
          (swap! blocks assoc index
                 (case (:type content_block)
                   "text" {:type :text :text (or (:text content_block) "")}
                   "thinking" {:type :thinking :thinking (or (:thinking content_block) "")
                               :signature (or (:signature content_block) "")}
                   "tool_use" {:type :tool-use
                               :id (:id content_block)
                               :name (:name content_block)
                               :input-json ""}
                   {:type :text :text ""}))
          (when (= "tool_use" (:type content_block))
            (when on-event
              (on-event {:type :tool-use-start
                         :id (:id content_block)
                         :name (:name content_block)}))))

        "content_block_delta"
        (let [{:keys [index delta]} event
              path [index]]
          (case (:type delta)
            "text_delta"
            (do (swap! blocks update-in (conj path :text) str (:text delta))
                (when on-event (on-event {:type :text-delta :text (:text delta)})))

            "thinking_delta"
            (do (swap! blocks update-in (conj path :thinking) str (:thinking delta))
                (when on-event (on-event {:type :thinking-delta :text (:thinking delta)})))

            "signature_delta"
            (swap! blocks update-in (conj path :signature) str (:signature delta))

            "input_json_delta"
            (swap! blocks update-in (conj path :input-json) str (:partial_json delta))
            nil))

        "message_delta"
        (do (swap! usage merge (select-keys (:usage event) [:output_tokens]))
            (when-let [r (get-in event [:delta :stop_reason])] (reset! stop r)))

        nil))
    (let [content (->> @order
                       (keep @blocks)
                       (keep (fn [{:keys [type input-json] :as b}]
                               (if (= :tool-use type)
                                 (-> b
                                     (dissoc :input-json)
                                     (assoc :input (try (if (str/blank? input-json)
                                                          {}
                                                          (cheshire.core/parse-string input-json true))
                                                        (catch Exception _ {}))))
                                 b)))
                       (remove (fn [b] (and (= :text (:type b)) (str/blank? (:text b)))))
                       vec)]
      {:content content
       :stop-reason (or (stop->reason @stop)
                        (if (some llm/tool-use? content) :tool-use :end-turn))
       :model model
       :usage {:input-tokens (:input_tokens @usage)
               :output-tokens (:output_tokens @usage)}})))
