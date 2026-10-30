(ns shelley.llm
  "Provider-independent LLM data model, streaming transport, and provider dispatch.

  The model mirrors the Go original: a conversation is a vector of messages; a
  message is a role plus a vector of *content blocks*. Tool calls and tool
  results live inside those blocks, which is what lets one message carry
  several parallel tool calls.

    block  ::= {:type :text        :text string}
             | {:type :thinking    :thinking string}
             | {:type :tool-use    :id string :name string :input map}
             | {:type :tool-result :tool-use-id string
                                   :content [block] :is-error boolean}
             | {:type :image       :media-type string :data base64}

    message ::= {:role :user | :assistant | :system :content [block]}

  Streamed events, passed to the :on-event callback:
    {:type :text-delta     :text string}
    {:type :thinking-delta :text string}
    {:type :tool-use-start :id string :name string}

  A completed response is:
    {:content [block] :stop-reason kw :usage {...} :model string}"
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Content helpers

(defn text-block [s] {:type :text :text (str s)})
(defn text? [block] (= :text (:type block)))
(defn tool-use? [block] (= :tool-use (:type block)))
(defn tool-result? [block] (= :tool-result (:type block)))

(defn user-message
  "Build a user message from a string or a seq of blocks."
  [content]
  {:role :user :content (if (string? content) [(text-block content)] (vec content))})

(defn assistant-message [content]
  {:role :assistant :content (vec content)})

(defn normalize-block
  "Blocks are stored as JSON, where keyword values come back as strings.
  Re-keywordise :type (and nested tool-result content) after a round trip."
  [block]
  (cond-> block
    (:type block) (update :type keyword)
    (sequential? (:content block)) (update :content #(mapv normalize-block %))))

(defn text-of
  "Concatenate all text blocks in a message. Used for titles and search."
  [message]
  (apply str (map :text (filter text? (:content message)))))

(defn tool-uses [message]
  (filterv tool-use? (:content message)))

(defn ->api-tool
  "Normalise a tool definition to {:name :description :input-schema}."
  [{:keys [name description input-schema parameters] :as tool}]
  {:name name
   :description (or description "")
   :input-schema (or input-schema parameters {:type "object" :properties {}})})

;; ---------------------------------------------------------------------------
;; Ordering invariant used by every provider adapter

(defn merge-consecutive
  "Providers want strictly alternating turns. Consecutive messages with the
  same role (e.g. a tool-result message injected after a user message) are
  merged rather than rejected."
  [messages]
  (reduce (fn [acc {:keys [role content] :as m}]
            (let [last-m (peek acc)]
              (if (and last-m (= role (:role last-m)))
                (conj (pop acc) (update last-m :content into content))
                (conj acc m))))
          [] messages))

;; ---------------------------------------------------------------------------
;; Streaming transport (shared by all providers)

(defn- sse-data-lines
  "Read an SSE body, yielding the payload string of each event."
  [^java.io.BufferedReader rdr]
  (lazy-seq
   (let [payload (StringBuilder.)]
     (loop []
       (when-let [line (.readLine rdr)]
         (cond
           (empty? line)
           (when (pos? (.length payload))
             [(str payload)])

           (.startsWith line "data:")
           (do (.append payload (clojure.string/triml (subs line 5)))
               (recur))

           :else (recur))))
     (when-let [v (when (pos? (.length payload)) [(str payload)])]
       (concat v (sse-data-lines rdr))))))

(defn post-stream
  "POST JSON to `url` and return a lazy seq of SSE payload strings.

  The response body is read lazily on the calling thread, so the caller must
  consume the seq before returning. Throws ex-info with the provider's error
  body on a non-2xx status."
  [url headers body]
  (let [client (-> (java.net.http.HttpClient/newBuilder)
                   (.connectTimeout (java.time.Duration/ofSeconds 30))
                   (.build))
        req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                (.timeout (java.time.Duration/ofMinutes 15))
                (.header "content-type" "application/json")
                (.POST (java.net.http.HttpRequest$BodyPublishers/ofString
                        (json/generate-string body)))
                (as-> b (reduce (fn [b [k v]] (.header b k v)) b headers))
                (.build))
        resp (.send client req (java.net.http.HttpResponse$BodyHandlers/ofInputStream))
        status (.statusCode resp)
        rdr (java.io.BufferedReader.
             (java.io.InputStreamReader. (.body resp) java.nio.charset.StandardCharsets/UTF_8))]
    (when-not (<= 200 status 299)
      (let [txt (try (str/join "\n" (line-seq rdr)) (catch Exception _ ""))
            retry-after (some-> (.firstValue (.headers resp) "retry-after")
                                str/trim (parse-long))]
        (throw (ex-info (str "LLM HTTP " status ": " (subs txt 0 (min 800 (count txt))))
                        {:status status :body txt :url url :retry-after retry-after}))))
    (sse-data-lines rdr)))

(defn stream-json
  "POST and yield decoded SSE JSON chunks, skipping [DONE] and blank keepalives."
  [url headers body]
  (->> (post-stream url headers body)
       (keep (fn [payload]
               (when-not (= payload "[DONE]")
                 (try (json/parse-string payload true)
                      (catch Exception _ nil)))))))

(defn retryable? [^Throwable e]
  (or (instance? java.io.IOException e)
      (instance? java.net.http.HttpConnectTimeoutException e)
      (when-let [s (:status (ex-data e))]
        (or (= 429 s) (<= 500 s 599) (= 408 s)))))

(defn- backoff-ms [attempt retry-after]
  (if retry-after
    (min 60000 (* 1000 (long retry-after)))
    (min 15000 (* 1000 (long (Math/pow 2 attempt))))))

(defn retrying
  "Call `f` (which must return a fully-consumed result) retrying transient
  failures — 429, 5xx, and connection errors — with exponential backoff.
  `on-retry` is called with (attempt, throwable) so the UI can show a warning."
  ([f] (retrying f nil 6))
  ([f on-retry] (retrying f on-retry 6))
  ([f on-retry max-attempts]
   (loop [attempt 0]
     (let [result (try {:ok (f)}
                       (catch clojure.lang.ExceptionInfo e
                         (if (retryable? e) {:err e} (throw e)))
                       (catch java.io.IOException e {:err e})
                       (catch java.net.http.HttpConnectTimeoutException e {:err e}))]
       (if (or (:ok result) (>= attempt max-attempts))
         (if (:ok result) (:ok result) (throw (:err result)))
         (do (when on-retry (on-retry attempt (:err result)))
             (Thread/sleep (backoff-ms attempt (:retry-after (ex-data (:err result)))))
             (recur (inc attempt))))))))

;; ---------------------------------------------------------------------------
;; Provider dispatch

(defn provider-for
  "Pick a wire protocol from the model id.

  Claude models talk the Anthropic wire format; predictable is the in-process
  fixture used by tests; everything else on the gateway speaks OpenAI chat
  completions."
  [model]
  (cond
    (str/starts-with? (str model) "predictable/") :predictable
    (re-find #"(?i)claude" (str model)) :anthropic
    (re-find #"(?i)gemini" (str model)) :gemini
    :else :openai))

(def ^:dynamic *base-url*
  (or (System/getenv "LLM_BASE_URL") "https://llm-exe-proxy.int.exe.xyz"))

(defn base-url [] *base-url*)

(defmulti send-request
  "Stream one request through a provider. Returns a completed response map and
  calls `on-event` with deltas as they arrive.

  Registered providers: :anthropic, :openai, :gemini, :predictable."
  (fn [request _on-event] (provider-for (:model request))))

(defmethod send-request :openai [request on-event]
  ((requiring-resolve 'shelley.llm.openai/send-request) request on-event))

(defmethod send-request :anthropic [request on-event]
  ((requiring-resolve 'shelley.llm.anthropic/send-request) request on-event))

(defmethod send-request :gemini [request on-event]
  ((requiring-resolve 'shelley.llm.gemini/send-request) request on-event))

(defmethod send-request :predictable [request on-event]
  ((requiring-resolve 'shelley.llm.fixture/send-request) request on-event))

(defn complete
  "Send one request and return a completed response map.

  opts: :model :system (vector of strings) :messages :tools
        :max-tokens :temperature :thinking-level :prompt-caching
        :on-event (fn [event]) :api-key :base-url :on-retry"
  [{:keys [model on-event] :as request}]
  (if (nil? model)
    (throw (ex-info "no model configured (set CLJ_SHELLEY_MODEL)" {}))
    (retrying #(send-request request on-event) (:on-retry request))))

;; ---------------------------------------------------------------------------
;; Context accounting

(defn estimate-tokens
  "Rough token count for a message vector, for deciding when to compact.

  Character-based with a per-block overhead, which tracks real tokenizers well
  enough for a threshold and costs nothing. Shared with the UI's context bar."
  [messages]
  (reduce
   (fn [total {:keys [content]}]
     (+ total
        (reduce (fn [n block]
                  (+ n (case (:type block)
                         :text (long (Math/ceil (/ (+ 4 (count (str (:text block)))) 4.0)))
                         :thinking (long (Math/ceil (/ (count (str (:thinking block))) 6.0)))
                         :tool-use (+ 12 (long (Math/ceil (/ (count (pr-str (:input block))) 4.0))))
                         :tool-result (estimate-tokens [{:content (:content block)}])
                         :image 1600  ; a picture costs roughly this much
                         8)))
                0 content)))
   0 messages))

(defn usage-total
  "Fold per-call usage maps into one. Missing keys are treated as zero."
  [usages]
  (reduce (fn [acc u]
            (merge-with (fn [a b] (+ (or a 0) (or b 0)))
                        acc
                        (select-keys (or u {}) [:input-tokens :output-tokens :cache-read-tokens
                                                :cache-write-tokens])))
          {:input-tokens 0 :output-tokens 0 :cache-read-tokens 0 :cache-write-tokens 0}
          usages))

(def thinking-budgets
  "Thinking levels map to provider budgets. `nil` means do not send a thinking
  parameter at all, which is what most models want."
  {:off nil :minimal nil :low 4000 :medium 8000 :high 16000 :xhigh 32000 :max 64000})

(defn thinking-budget [level]
  (thinking-budgets (or level :off)))
