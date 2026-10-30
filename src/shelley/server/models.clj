(ns shelley.server.models
  "The model catalogue, custom models, pricing, and the tools list.

  Model ids are `<provider>/<model>`. The catalogue comes from the exe.dev LLM
  gateway's `/models.json`, which is authoritative for what this VM can call
  but carries no prices, so pricing stays in `shelley.usage`.

  The gateway is fetched with a short timeout and cached to disk. A machine
  with no route to it must still start and still list something plausible,
  which is what the builtin list is for: an empty model picker is a worse
  failure than a stale one."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [shelley.db :as db]
            [shelley.llm :as llm]
            [shelley.server.http :as http]
            [shelley.tools :as tools]
            [shelley.usage :as usage])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpClient$Redirect
                          HttpRequest$BodyPublishers)
           (java.time Duration)))

(def gateway-models-url
  (or (System/getenv "CLJ_SHELLEY_MODELS_URL")
      "https://llm-exe-proxy.int.exe.xyz/models.json"))

(def cache-file (str (or (System/getenv "CLJ_SHELLEY_CACHE_DIR") "/tmp/clj-shelley") "/models-cache.json"))

(def cache-ttl-ms
  "How long a fetched catalogue is trusted. Long enough that a page load does
  not wait on the network, short enough that a new model appears the same day."
  (* 5 60 1000))

(def ^:private fetch-timeout (Duration/ofSeconds 5))

(def builtin-models
  "The floor: enough to start a conversation with nothing reachable."
  [{:id "anthropic/claude-sonnet-5-5" :name "Claude Sonnet 5.5" :provider "anthropic"
    :context_window 200000 :max_output_tokens 64000 :thinking true :images true}
   {:id "anthropic/claude-opus-5-5" :name "Claude Opus 5.5" :provider "anthropic"
    :context_window 200000 :max_output_tokens 64000 :thinking true :images true}
   {:id "anthropic/claude-haiku-5-5" :name "Claude Haiku 5.5" :provider "anthropic"
    :context_window 200000 :max_output_tokens 32000 :thinking true :images true}
   {:id "openai/gpt-6.1-sol" :name "GPT-6.1 Sol" :provider "openai"
    :context_window 400000 :max_output_tokens 128000 :thinking true :images true}])

(defonce ^:private memory-cache (atom nil))

(declare provider-ids-from)

(defn- http-client ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 3))
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.build)))

(defn- fetch-json [url]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout fetch-timeout)
                (.header "accept" "application/json")
                (.GET)
                (.build))
        resp (.send (http-client) req (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode resp))
      (json/parse-string (.body resp) true))))

(defn- read-cache-file []
  (try
    (when (.exists (java.io.File. cache-file))
      (json/parse-string (slurp cache-file) true))
    (catch Exception _ nil)))

(defn- write-cache-file! [v]
  (try
    (let [f (java.io.File. cache-file)]
      (.mkdirs (.getParentFile f))
      (spit f (json/generate-string v)))
    (catch Exception _ nil)))

(defn- normalise-model [m]
  (let [provider (or (:provider m)
                     (some-> (:id m) (str/split #"/" 2) first))
        modalities (get-in m [:architecture :input_modalities])
        params (set (map name (or (:supported_parameters m) [])))]
    {:id (:id m)
     :name (or (:name m) (:id m))
     :display_name (or (:name m) (:id m))
     :provider provider
     :native_id (:native_id m)
     :context_window (get-in m [:limits :context_window])
     :max_output_tokens (get-in m [:limits :max_output_tokens])
     :thinking (or (contains? params "reasoning")
                   (contains? params "thinking")
                   (boolean (get-in m [:exe_dev :reasoning])))
     :images (or (some #{"image"} modalities) false)
     :apis (vec (or (:apis m) []))}))

(defn- chat-capable?
  "A model the chat completions path can actually drive. Embedding and speech
  models appear in the gateway catalogue and are not selectable here."
  [m]
  (let [apis (set (map name (or (:apis m) [])))
        prov (str (:provider m))]
    (and (:id m)
         (not (#{"deepgram"} prov))
         (or (empty? apis)
             (some apis ["openai_chat" "anthropic_messages" "messages" "chat"])))))

(defn fetch-catalogue
  "The gateway catalogue, or nil if it cannot be reached."
  []
  (try
    (when-let [body (fetch-json gateway-models-url)]
      (let [models (mapv normalise-model (filter chat-capable? (or (:models body) [])))]
        (when (seq models)
          {:models models
           :providers (provider-ids-from (:providers body) models)
           :preferred_model (:preferred_model body)
           :source :gateway
           :fetched_at (http/now-iso)})))
    (catch Exception _ nil)))

(defn catalogue
  "The model catalogue, from memory, then the gateway, then the disk cache,
  then the builtin list — in that order of freshness."
  []
  (let [now (System/currentTimeMillis)
        cached @memory-cache]
    (cond
      (and cached (< (- now (:at cached)) cache-ttl-ms)) (:value cached)

      :else
      (let [fetched (or (fetch-catalogue)
                        (when-let [disk (read-cache-file)]
                          (when (seq (:models disk))
                            (-> disk
                                (update :models #(mapv normalise-model (filter chat-capable? %)))
                                (assoc :source :cache))))
                        {:models builtin-models :providers ["anthropic" "openai"]
                         :source :builtin :fetched_at nil})]
        (reset! memory-cache {:at now :value fetched})
        (when (= :gateway (:source fetched)) (write-cache-file! fetched))
        fetched))))

(defn models [] (:models (catalogue)))

(defn find-model [model-id]
  (when model-id
    (or (some #(when (= model-id (:id %)) %) (models))
        (some #(when (= model-id (:id %)) %) builtin-models))))

(defn context-window
  "How many tokens this model can hold. A custom model knows its own limit, and
  that is the number the context meter should use — the gateway catalogue has
  never heard of it."
  ([model-id] (context-window model-id 200000))
  ([model-id default]
   (or (when-let [m (db/get-model model-id)]
         (let [n (or (:max-tokens m) (:max_tokens m))]
           (when (and n (pos? (long n))) n)))
       (:context_window (find-model model-id))
       default)))

(defn display-name [model-id]
  (or (:name (find-model model-id)) model-id))

(defn default-model-id
  "Which model a conversation gets when the client does not name one.

  Order: the user's saved choice, then CLJ_SHELLEY_MODEL, then the gateway's
  preference, then a builtin. The environment step matters because the gateway's
  preference is often a reasoning model, and those refuse function tools on chat
  completions — a bad first-run experience for an agent whose whole job is
  calling tools."
  []
  (or (not-empty (str (:model (db/get-setting :model))))
      (not-empty (str (System/getenv "CLJ_SHELLEY_MODEL")))
      (:preferred_model (catalogue))
      "fireworks/deepseek-v4p1-flash-1017"))

;; ---------------------------------------------------------------------------
;; Custom models

(defn- custom->api [m]
  (http/compact-map
   {"id" (:model-id m)
    "model_id" (:model-id m)
    "display_name" (:display-name m)
    "provider_type" (:provider-type m)
    "endpoint" (:endpoint m)
    "model_name" (:model-name m)
    "max_tokens" (:max-tokens m)
    "reasoning_effort" (:reasoning-effort m)
    "image_support" (boolean (:image-support m))
    "reasoning_support" (boolean (:reasoning-support m))
    "reasoning_map" (http/parse-json-object (:reasoning-map m))
    "reasoning_replay" (boolean (:reasoning-replay m))
    "has_api_key" (boolean (seq (str (:api-key m))))
    "custom" true}))

(defn- api->custom [body]
  (cond-> {}
    (:display_name body) (assoc :display-name (:display_name body))
    (:provider_type body) (assoc :provider-type (:provider_type body))
    (:endpoint body) (assoc :endpoint (:endpoint body))
    (:api_key body) (assoc :api-key (:api_key body))
    (:model_name body) (assoc :model-name (:model_name body))
    (:max_tokens body) (assoc :max-tokens (:max_tokens body))
    (:reasoning_effort body) (assoc :reasoning-effort (:reasoning_effort body))
    (contains? body :image_support) (assoc :image-support (http/truthy? (:image_support body)))
    (contains? body :reasoning_support) (assoc :reasoning-support (http/truthy? (:reasoning_support body)))
    (:reasoning_map body) (assoc :reasoning-map (json/generate-string (:reasoning_map body)))
    (contains? body :reasoning_replay) (assoc :reasoning-replay (http/truthy? (:reasoning_replay body)))))

;; ---------------------------------------------------------------------------
;; Custom models → llm routes
;;
;; A model the user added has its own endpoint and key. Registering it with the
;; llm layer is what makes an id like `deepseek-flash-api-deepseek-com` reach
;; api.deepseek.com instead of the shared gateway.

(def ^:private provider->llm
  {"openai" :openai
   "openai-responses" :openai-responses
   "anthropic" :anthropic
   "gemini" :gemini})

(defn- bare-endpoint
  "Endpoints are stored however the user typed them; the adapters append the
  protocol path, so a trailing /v1 here would become /v1/v1."
  [endpoint]
  (-> (str endpoint)
      (str/replace #"/+$" "")
      (str/replace #"/v1$" "")))

(defn reload-model-routes!
  "Re-register every custom model with the llm layer."
  []
  (llm/clear-routes!)
  (doseq [m (db/list-models)]
    ;; The row carries snake_case keys as well as kebab ones, and the API key is
    ;; only in the snake spelling — reading one spelling silently registered
    ;; nothing and the turn went to the gateway instead.
    (let [endpoint (bare-endpoint (:endpoint m))
          key (or (:api-key m) (:api_key m))]
      (when (and (not (str/blank? endpoint)) (not (str/blank? (str key))))
        (llm/register-route! (:model-id m)
                             (cond-> {:provider (get provider->llm (:provider-type m) :openai)
                                      :base-url endpoint
                                      :api-key key
                                      :wire-model (or (not-empty (str (:model-name m)))
                                                      (:model-id m))}
                               (:max-tokens m) (assoc :max-tokens (:max-tokens m))))))))

(defn handler-list-custom-models [_]
  (http/ok {"models" (mapv custom->api (db/list-models))}))

(defn handler-save-custom-model
  "POST /api/custom-models — create or replace one custom model."
  [req]
  (let [body (http/parse-body req)
        id (or (:id body) (:model_id body) (:model-id body))]
    (if (str/blank? (str id))
      (http/bad-request "id is required")
      (let [spec (merge {:model-id id} (api->custom body))]
        (db/save-model! spec)
        (reload-model-routes!)
        (http/ok (custom->api (db/get-model id)))))))

(defn handler-update-custom-model [req]
  (let [id (http/path-param req :id)
        existing (db/get-model id)]
    (if (nil? existing)
      (http/not-found "custom model not found")
      (let [body (http/parse-body req)]
        (db/save-model! (merge {:model-id id} (api->custom body)))
        (reload-model-routes!)
        (http/ok (custom->api (db/get-model id)))))))

(defn handler-delete-custom-model [req]
  (let [id (http/path-param req :id)]
    (if (nil? (db/get-model id))
      (http/not-found "custom model not found")
      (do (db/delete-model! id)
          (reload-model-routes!)
          (http/ok {"status" "deleted" "id" id})))))

(defn handler-test-custom-model
  "POST /api/custom-models-test — check that an endpoint answers.

  A custom model is configured by hand, so the most common failure is a typo in
  the endpoint or a missing key. This makes one small request and reports what
  came back rather than waiting for the first real turn to fail."
  [req]
  (let [body (http/parse-body req)
        endpoint (or (:endpoint body) (:base_url body))
        key (or (:api_key body) (:api-key body))
        model (or (:model_name body) (:model-name body) "test")]
    (cond
      (str/blank? (str endpoint)) (http/bad-request "endpoint is required")
      :else
      (try
        (let [started (System/currentTimeMillis)
              payload (json/generate-string {:model model :max_tokens 1
                                             :messages [{:role "user" :content "ping"}]})
              req' (cond-> (-> (HttpRequest/newBuilder (URI/create (str/replace endpoint #"/+$" "")))
                               (.timeout (Duration/ofSeconds 15))
                               (.header "content-type" "application/json")
                               (.POST (HttpRequest$BodyPublishers/ofString payload)))
                     (seq (str key)) (.header "authorization" (str "Bearer " key)))
              resp (.send (http-client) (.build req') (HttpResponse$BodyHandlers/ofString))
              ok? (<= 200 (.statusCode resp) 299)]
          (http/ok {"ok" ok?
                    "status" (.statusCode resp)
                    "latency_ms" (- (System/currentTimeMillis) started)
                    "detail" (subs (str (.body resp)) 0 (min 500 (count (str (.body resp)))))}))
        (catch Exception e
          (http/ok {"ok" false "error" (.getMessage e)}))))))

;; ---------------------------------------------------------------------------
;; Provider / catalogue endpoints

(defn provider-ids-from
  "Provider ids from a catalogue's `providers` field, whichever shape it has: a
  map keyed by provider, a vector of those entries, a list of objects, or
  nothing at all. All four appear in the wild, and getting this wrong produces
  one provider named `[:anthropic {...}]`."
  [ps models]
  (let [entry-id (fn [x]
                   (cond
                     (map-entry? x) (str (name (key x)))
                     (map? x) (str (or (:id x) (:provider x) (:name x)))
                     :else (str x)))]
    (cond
      (map? ps) (mapv (fn [k] (str (name k))) (keys ps))
      (sequential? ps) (mapv entry-id ps)
      :else (vec (distinct (map :provider models))))))

(defn- provider-ids [cat] (provider-ids-from (:providers cat) (:models cat)))

(defn- provider-rows []
  (let [cat (catalogue)
        by-provider (group-by :provider (:models cat))
        custom (group-by :provider-type (db/list-models))]
    (vec (for [p (distinct (concat (provider-ids cat) (keys by-provider) (keys custom)))]
           (http/compact-map
            {"provider_id" p
             "provider_type" p
             "display_name" (str/capitalize (str p))
             "configured" (boolean (seq (get by-provider p)))
             "models" (vec (concat
                            (for [m (get by-provider p)]
                              (http/compact-map
                               {"id" (:id m) "name" (:name m) "display_name" (:display_name m)
                                "provider" (:provider m)
                                "context_window" (:context_window m)
                                "max_output_tokens" (:max_output_tokens m)
                                "thinking" (:thinking m) "images" (:images m)
                                "source" (name (:source cat))}))
                            (for [m (get custom p)]
                              (custom->api m))))})))))

(def ^:private resold-deepseek
  "DeepSeek served by a third party. This deployment reaches DeepSeek on its own
  platform API, and listing the resold entries invites picking one by mistake:
  the two are the same model name and very different data paths."
  #{"fireworks/deepseek-v4p1-flash-1017" "fireworks/deepseek-v4p1-flash"})

(defn- models-payload
  "The model catalogue as one object. Shared by /api/models and
  /api/model-providers, which differ only in what they add."
  []
  (let [cat (catalogue)]
    (http/compact-map
              {"models" (vec (concat
                              (map #(http/compact-map
                                     {"id" (:id %) "name" (:name %) "display_name" (:display_name %)
                                      "provider" (:provider %) "context_window" (:context_window %)
                                      "max_output_tokens" (:max_output_tokens %)
                                      "thinking" (:thinking %) "images" (:images %)
                                      "source" (name (:source cat))})
                                   (remove #(contains? resold-deepseek (:id %)) (:models cat)))
                              (map custom->api (db/list-models))))
               "default_model" (default-model-id)
               "source" (name (:source cat))
               "fetched_at" (:fetched_at cat)})))

(defn handler-models [_]
  (http/ok (models-payload)))

(defn handler-model-providers
  "GET /api/model-providers — the catalogue grouped by provider, which is what
  a model picker needs: providers with their models, plus the flat list."
  [_]
  (let [cat (catalogue)
        payload (models-payload)]
    (http/ok (http/compact-map
              {"providers" (provider-rows)
               "models" (get payload "models")
               "default_model" (default-model-id)
               "source" (name (:source cat))
               "fetched_at" (:fetched_at cat)
               "refresh" true}))))

;; ---------------------------------------------------------------------------
;; Pricing and usage

(defn- price-row [model]
  (let [p (usage/price-for model)]
    {"model" model
     "display_name" (display-name model)
     "input" (:input p) "output" (:output p)
     "cache_read" (:cache-read p) "cache_write" (:cache-write p)
     "unit" "usd_per_million_tokens"}))

(defn handler-model-costs [_]
  (let [models (distinct (concat (keys usage/prices) (map :id (models)) (map :model-id (db/list-models))))]
    (http/ok {"unit" "usd_per_million_tokens"
              "currency" "USD"
              "costs" (into {} (for [m (keys usage/prices)]
                                 [m (let [p (usage/price-for m)]
                                      {"input" (:input p) "output" (:output p)
                                       "cache_read" (:cache-read p) "cache_write" (:cache-write p)})]))
              "models" (mapv price-row models)})))

(defn handler-compute-cost
  "POST /api/model-costs — price a set of usage rows, as the original does.
  Body: `{model, usage: [{input_tokens, output_tokens, cache_read_tokens, ...}]}`
  or a single usage map."
  [req]
  (let [body (http/parse-body req)
        model (or (:model body) (default-model-id))
        rows (or (:usage body) (:usages body) [body])
        rows (if (map? rows) [rows] (vec rows))
        totalled (mapv (fn [u]
                         {"input_tokens" (or (:input_tokens u) (:input-tokens u) 0)
                          "output_tokens" (or (:output_tokens u) (:output-tokens u) 0)
                          "cache_read_tokens" (or (:cache_read_tokens u) (:cache-read-tokens u) 0)
                          "cache_write_tokens" (or (:cache_write_tokens u) (:cache-write-tokens u) 0)})
                       rows)]
    (http/ok (http/compact-map
              {"model" model
               "llm_calls" (count totalled)
               "cost_usd" (usage/total-cost (map #(usage/cost % model) totalled))
               "priced" (usage/priced? model)
               "usage" totalled}))))

(defn- row->usage [u]
  {:input-tokens (or (:input_tokens u) (:input-tokens u) 0)
   :output-tokens (or (:output_tokens u) (:output-tokens u) 0)
   :cache-read-tokens (or (:cache_read_tokens u) (:cache-read-tokens u) 0)
   :cache-write-tokens (or (:cache_write_tokens u) (:cache-write-tokens u) 0)})

(defn conversation-usage
  "Totals for one conversation, from the persisted `usage_data` on its rows."
  [conv-id]
  (let [rows (db/messages-by-type conv-id :agent)]
    (reduce (fn [acc m]
              (let [u (http/parse-json-object (:usage-data m))]
                (if u
                  (as-> acc a
                    (update a :llm_calls inc)
                    (update a :input_tokens + (or (:input_tokens u) (:input-tokens u) 0))
                    (update a :output_tokens + (or (:output_tokens u) (:output-tokens u) 0))
                    (update a :cache_read_tokens + (or (:cache_read_tokens u) (:cache-read-tokens u) 0))
                    (update a :cache_write_tokens + (or (:cache_write_tokens u) (:cache-write-tokens u) 0))
                    ;; A row from an unpriced model makes the total unknown, not
                    ;; partial: a sum of the rows we happen to have prices for
                    ;; would read as the whole cost of the conversation.
                    (update a :cost_usd
                            (fn [cur]
                              (let [c (or (:cost_usd u) (:cost-usd u)
                                          (usage/cost (row->usage u) (:model-name m)))]
                                (when (and (some? cur) (some? c)) (+ cur c))))))
                  acc)))
            {:llm_calls 0 :input_tokens 0 :output_tokens 0 :cache_read_tokens 0
             :cache_write_tokens 0 :cost_usd 0.0}
            rows)))

(defn handler-usage
  "GET /api/conversation/:id/usage — totals for one conversation.

  The path parameter is `:id`; reading `:conversation` returned nil and the
  endpoint answered zeros with `conversation_id: null`, which reads as
  a free conversation rather than a missing one."
  [req]
  ;; Two shapes reach this handler: the REST-ish path and `?conversation=`,
  ;; which is what a client that guesses at the API tries first. Answering
  ;; "not found" to the second made the usage bar read "unavailable" on a
  ;; conversation that had plenty of usage.
  (let [conv-id (or (http/path-param req :id) (get (http/query-params req) :conversation))
        conv (db/get-conversation conv-id)]
    (if (nil? conv)
      (http/not-found "conversation not found")
      (let [base (conversation-usage conv-id)]
        (http/ok (assoc base
                        "conversation_id" conv-id
                        "model" (:model conv)
                        "context_window_size" (context-window (:model conv))))))))

;; ---------------------------------------------------------------------------
;; Tools

(defn handler-tools [_]
  (http/ok {"tools" (mapv (fn [t]
                            (http/compact-map
                             {"name" (or (:name t) (get-in t ["name"]))
                              "description" (or (:description t) (get-in t ["description"]))
                              "input_schema" (or (:input_schema t) (:inputSchema t) (get-in t ["input_schema"]))}))
                          (tools/tool-defs :default))}))

(def routes
  [["/api/models" {:get handler-models}]
   ["/api/model-providers" {:get handler-model-providers}]
   ;; Aliases: the catalogue as a resource collection, which is what a client
   ;; reaching for "the models API" tries first.
   ["/api/models/providers" {:get handler-model-providers}]
   ["/api/models/costs" {:get handler-model-costs :post handler-compute-cost}]
   ["/api/model-costs" {:get handler-model-costs :post handler-compute-cost}]
   ["/api/custom-models" {:get handler-list-custom-models :post handler-save-custom-model}]
   ["/api/models/custom" {:get handler-list-custom-models :post handler-save-custom-model}]
   ["/api/custom-models/:id" {:put handler-update-custom-model
                              :post handler-update-custom-model
                              :delete handler-delete-custom-model}]
   ["/api/custom-models-test" {:post handler-test-custom-model}]
   ["/api/tools" {:get handler-tools}]
   ["/api/usage" {:get handler-usage}]
   ["/api/conversation/:id/usage" {:get handler-usage}]])
