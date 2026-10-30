(ns shelley.loop
  "The agent loop.

  One *turn* is: ask the model, run whatever tools it asked for, feed the
  results back, repeat until it stops asking for tools. That is the whole
  agent. Everything else in this repository exists to serve this loop:
  persistence to survive a crash mid-turn, streaming so the user sees progress,
  cancellation so a runaway turn can be stopped.

  Tool execution is parallel within a round — the model frequently emits three
  independent bash calls, and serialising them wastes wall-clock time.

  Three things make a long-running turn behave like the original's:

  * **queue** — user messages typed while the agent is working are spliced in
    before the next request rather than waiting for the turn to end;
  * **inject** — a function called between rounds, used to splice subagent
    completion notices into the conversation as soon as they happen;
  * **compaction** — when the context estimate crosses the limit, older
    messages collapse into a summary instead of the turn failing on a
    provider-side length error."
  (:require [shelley.llm :as llm]
            [shelley.tools :as tools]
            [shelley.tools.compact :as compact]
            [clojure.tools.logging :as log]))

(def max-iterations 100)

(def ^:private compaction-threshold
  "Compact when the estimate reaches this fraction of the limit. Leaving
  headroom matters: the estimate is approximate and one tool result can be
  large."
  0.8)

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
                                ;; Tools that rewrite context (compact_in_place)
                                ;; need to see the conversation they are editing.
                                result (tools/execute (assoc ctx :messages @(:history ctx)) block)
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
;; Between-round inputs

(defn- drain-queue!
  "Take any user messages queued during this turn. Returns messages to append."
  [queue]
  (when queue
    (let [pending @queue]
      (reset! queue [])
      (mapv llm/user-message pending))))

(defn- injected-messages
  "Messages a hook wants spliced in before the next request."
  [inject]
  (when inject
    (let [r (inject)]
      (cond (nil? r) []
            (map? r) [r]
            :else (vec r)))))

(defn- maybe-compact!
  "Collapse history when it approaches the model's limit. `compact-fn` receives
  the message vector and returns a shorter one; it must preserve the last turn."
  [history context-limit compact-fn emit]
  (when (and context-limit compact-fn)
    (let [estimate (llm/estimate-tokens @history)]
      (when (> estimate (* compaction-threshold context-limit))
        (emit {:type :compaction-start :estimated-tokens estimate})
        (let [before (count @history)
              compacted (compact-fn @history)]
          (reset! history compacted)
          (emit {:type :compaction-end
                 :messages-before before
                 :messages-after (count compacted)}))))))

;; ---------------------------------------------------------------------------
;; Turn

(defn run-turn
  "Drive one user turn to completion.

  opts:
    :model        model id
    :system       vector of system strings, or a fn of no args returning them
    :messages     conversation history (vector of messages)
    :tools        tool definitions (from shelley.tools/tool-defs)
    :ctx          tool execution context
    :emit         (fn [event]) — streaming and tool events for the UI
    :record!      (fn [message]) — persist a message
    :record-error! (fn [message]) — persist a failed-turn error row
    :cancelled?   (fn [] boolean)
    :queue        atom holding queued user message strings
    :inject       (fn [] message | [messages] | nil), called between rounds
    :compact-fn   (fn [messages] -> messages)
    :context-limit token budget for the model
    :thinking-level  :off :low :medium :high :xhigh :max
    :max-tokens   per-response cap

  Returns {:messages [...] :status :complete|:cancelled|:error :error msg
           :usage {...}}."
  [{:keys [model system messages tools ctx emit record! record-error! cancelled?
           queue inject compact-fn context-limit thinking-level max-tokens]
    :or {max-tokens 32000}}]
  (let [history (atom (vec messages))
        emit (or emit (fn [_]))
        cancelled? (or cancelled? (constantly false))
        usage (atom [])
        system-fn (if (fn? system) system (constantly system))]
    (try
      (loop [iteration 0]
        (cond
          (>= iteration max-iterations)
          (throw (ex-info (str "agent loop exceeded " max-iterations " iterations") {}))

          (cancelled?)
          (do (emit {:type :turn-end :status :cancelled})
              {:status :cancelled :messages @history :usage (llm/usage-total @usage)})

          :else
          (do
            ;; Inputs that arrived while the previous round was running.
            (doseq [m (injected-messages inject)] (swap! history conj m))
            (doseq [m (drain-queue! queue)]
              (swap! history conj m)
              (when record! (record! m)))

            (maybe-compact! history context-limit compact-fn emit)

            (let [request {:model model
                           :system (system-fn)
                           :messages (ensure-tool-results @history)
                           :tools tools
                           :max-tokens max-tokens
                           :thinking-level thinking-level
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
              (swap! usage conj (:usage resp))
              (swap! history conj assistant)
              (when record! (record! (assoc assistant :usage (:usage resp))))

              (if (empty? tool-uses)
                (do (emit {:type :turn-end :status :complete :usage (:usage resp)})
                    {:status :complete
                     :messages @history
                     :usage (llm/usage-total @usage)
                     :stop-reason (:stop-reason resp)})
                (let [results (execute-round (assoc ctx :history history) tool-uses emit)
                      ;; A tool may hand the loop work to do — compaction is the
                      ;; one case: the tool decides what to cut, the loop owns
                      ;; the history, so the tool returns a directive and the
                      ;; loop applies it here.
                      _ (doseq [{:keys [compact]} (keep :directive results)]
                          (when compact
                            (when-let [apply-fn (:apply-compact ctx)]
                              (apply-fn compact))
                            (when (= :compact (:action compact))
                              (swap! history compact/apply-directive compact))))
                      ;; A UI payload (iframe) is for the human, not the model,
                      ;; and a directive is for the loop, not the model.
                      cleaned (mapv #(dissoc % :ui :directive) results)
                      tool-message {:role :user :content cleaned}]
                  (swap! history conj tool-message)
                  (when record! (record! tool-message))
                  (recur (inc iteration))))))))
      (catch Exception e
        (let [msg (.getMessage e)
              retryable (llm/retryable? e)
              error-message {:role :assistant
                             :content [(llm/text-block (str "The request failed: " msg))]
                             :error {:type :llm-request :retryable retryable}}]
          (emit {:type :error :text msg :retryable retryable})
          (emit {:type :turn-end :status :error})
          (log/warn e "turn failed")
          (when record-error!
            (try (record-error! error-message)
                 (catch Exception persist-failure
                   (log/warn persist-failure "could not persist error row"))))
          {:status :error :error msg :messages @history
           :usage (llm/usage-total @usage)})))))
