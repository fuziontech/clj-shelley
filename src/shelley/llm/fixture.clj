(ns shelley.llm.fixture
  "A deterministic provider for tests: models named `predictable/...` play back
  scripted responses instead of calling the network.

  The original has the same idea in llm/predictable, and it is the reason its
  loop tests are fast and hermetic. Scripts are queued per model; when a queue
  empties, the provider answers with a plain end-of-turn message."
  (:require [shelley.llm :as llm]))

(defonce ^:private scripts (atom {}))

(defn register!
  "Queue responses for a model. Each response is {:content [...] :stop-reason kw}."
  [model responses]
  (swap! scripts assoc model (vec responses)))

(defn reset-scripts! [] (clojure.core/reset! scripts {}))

(defn send-request [{:keys [model] :as _request} on-event]
  (let [[resp remaining] (swap! scripts
                                (fn [all]
                                  (let [q (get all model [])]
                                    [(first q) (assoc all model (vec (rest q)))])))]
    (when (and resp on-event)
      (doseq [block (:content resp)]
        (when (= :text (:type block))
          (on-event {:type :text-delta :text (:text block)}))))
    (or resp
        {:content [(llm/text-block "done")]
         :stop-reason :end-turn
         :usage {:input-tokens 0 :output-tokens 0}})))
