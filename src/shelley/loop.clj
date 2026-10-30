(ns shelley.loop
  "The agent loop.

  One *turn* is: ask the model, run whatever tools it asked for, feed the
  results back, repeat until it stops asking for tools. That is the whole
  agent. Everything else in this repository exists to serve this loop:
  persistence to survive a crash mid-turn, streaming so the user sees progress,
  and cancellation so a runaway turn can be stopped.

  Tool execution is parallel within a round — the model frequently emits three
  independent bash calls, and serialising them wastes wall-clock time."
  (:require [shelley.llm :as llm]
            [shelley.tools :as tools]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def max-iterations 100)

;; ---------------------------------------------------------------------------
;; Tool execution

(defn- execute-round
  "Run every tool-use block in `blocks` concurrently. Returns result blocks in
  the same order the model asked for them."
  [ctx blocks emit]
  (let [futures (mapv (fn [block]
                        (future
                          (emit {:type :tool-start :id (:id block) :name (:name block)
                                 :input (:input block)})
                          (let [started (System/currentTimeMillis)
                                result (tools/execute ctx block)
                                elapsed (- (System/currentTimeMillis) started)]
                            (emit {:type :tool-end :id (:id block) :name (:name block)
                                   :is-error (:is-error result)
                                   :content (mapv :text (filter llm/text? (:content result)))
                                   :ui (:ui result)
                                   :elapsed-ms elapsed})
                            result)))
                      blocks)]
    (mapv deref futures)))

(defn- ensure-tool-results
  "If an assistant message requested tools but we somehow have no matching
  results (a crash, a cancel, a provider hiccup), synthesise error results so
  the conversation stays valid on the next request. Providers reject a tool_use
  with no tool_result."
  [messages]
  (if-let [last-assistant (last (filter #(= :assistant (:role %)) messages))]
    (let [pending (->> (llm/tool-uses last-assistant)
                       (remove (fn [{:keys [id]}]
                                 (some #(and (llm/tool-result? %) (= id (:tool-use-id %)))
                                       (:content (last messages))))))
          placeholder (mapv (fn [b] {:type :tool-result :tool-use-id (:id b) :is-error true
                                     :content [(llm/text-block "tool did not complete (interrupted)")]})
                            pending)]
      (if (seq placeholder)
        (conj (vec messages) {:role :user :content placeholder})
        messages))
    messages))

;; ---------------------------------------------------------------------------
;; Turn

(defn run-turn
  "Drive one user turn to completion.

  opts:
    :model        model id
    :system       vector of system strings
    :messages     conversation history (vector of messages)
    :tools        tool definitions (from shelley.tools/tool-defs)
    :ctx          tool execution context
    :emit         (fn [event]) — streaming and tool events for the UI
    :record!      (fn [message]) — persist an assistant/user message
    :cancel       (promise-like atom; when true the turn aborts)

  Returns {:messages [...] :status :complete|:cancelled|:error :error msg}."
  [{:keys [model system messages tools ctx emit record! cancelled? max-tokens]
    :or {max-tokens 32000}}]
  (let [history (atom (vec messages))
        emit (or emit (fn [_]))
        cancelled? (or cancelled? (constantly false))]
    (try
      (loop [iteration 0]
        (cond
          (>= iteration max-iterations)
          (throw (ex-info (str "agent loop exceeded " max-iterations " iterations") {}))

          (cancelled?)
          (do (emit {:type :turn-end :status :cancelled})
              {:status :cancelled :messages @history})

          :else
          (let [request {:model model
                         :system system
                       :messages (ensure-tool-results @history)
                       :tools tools
                       :max-tokens max-tokens
                       :on-event (fn [event]
                                   (when (and (not= :thinking-delta (:type event))
                                              (not (cancelled?)))
                                     (emit event)))
                       :on-retry (fn [attempt e]
                                   (emit {:type :warning
                                          :text (str "retrying after error: "
                                                     (.getMessage ^Throwable e)
                                                     " (attempt " (inc attempt) ")")}))}
              resp (llm/complete request)
              assistant {:role :assistant :content (:content resp)}
              tool-uses (llm/tool-uses assistant)]
          (swap! history conj assistant)
          (when record! (record! assistant))

          (if (empty? tool-uses)
            (do (emit {:type :turn-end :status :complete :usage (:usage resp)})
                {:status :complete :messages @history :usage (:usage resp)})
            (let [results (execute-round ctx tool-uses emit)
                  ;; A UI payload (iframe) is for the human, not the model.
                  cleaned (mapv #(dissoc % :ui) results)
                  tool-message {:role :user :content cleaned}]
              (swap! history conj tool-message)
              (when record! (record! tool-message))
              (recur (inc iteration)))))))
      (catch Exception e
        (let [msg (.getMessage e)]
          (emit {:type :error :text msg})
          (emit {:type :turn-end :status :error})
          (log/warn e "turn failed")
          {:status :error :error msg :messages @history})))))
