(ns shelley.usage
  "Token usage and cost accounting.

  The original stores per-message `usage_data` and exposes /api/model-costs so
  the UI can show what a conversation has cost. Prices move, so they live in one
  table here and are returned by the API rather than being hardcoded in the UI."
  (:require [shelley.llm :as llm]))

(def prices
  "USD per million tokens: {:input :output :cache-read :cache-write}.
  Approximate list prices; the gateway is the source of truth for billing."
  {"anthropic/claude-sonnet-5-5" {:input 3.0 :output 15.0 :cache-read 0.3 :cache-write 3.75}
   "anthropic/claude-opus-5-5"   {:input 15.0 :output 75.0 :cache-read 1.5 :cache-write 18.75}
   "anthropic/claude-haiku-5-5"  {:input 1.0 :output 5.0 :cache-read 0.1 :cache-write 1.25}
   "openai/gpt-6.1-sol"          {:input 2.5 :output 10.0 :cache-read 1.25 :cache-write 0}
   "openai/gpt-5.5"              {:input 2.5 :output 10.0 :cache-read 1.25 :cache-write 0}
   "deepseek/deepseek-v4-pro"    {:input 0.6 :output 2.4 :cache-read 0.15 :cache-write 0}})

(defn price-for
  "Prices for a model, or nil when we have none. Nil is deliberate: reporting
  $0.00 for a model we have no price for is a wrong number, and a wrong number
  is worse than an honest dash."
  [model]
  (get prices model))

(defn priced? [model]
  (some? (price-for model)))

(defn cost
  "USD for one usage map, given a model."
  [usage model]
  (when-let [p (price-for model)]
    (let [per-m (fn [tokens rate] (* (/ (double (or tokens 0)) 1e6) (double rate)))]
      (+ (per-m (:input-tokens usage) (:input p))
         (per-m (:output-tokens usage) (:output p))
         (per-m (:cache-read-tokens usage) (:cache-read p))
         (per-m (:cache-write-tokens usage) (:cache-write p))))))

(defn summarize
  "Totals and cost for a sequence of usage maps."
  [usages model]
  (let [total (llm/usage-total usages)]
    (assoc total :cost-usd (cost total model) :priced (priced? model) :model model)))

(defn total-cost
  "Sum a sequence of costs. Any unpriced component makes the total unknown:
  adding up what we know and calling it the whole is the same lie as $0.00."
  [costs]
  (when (every? some? costs) (reduce + 0.0 costs)))
