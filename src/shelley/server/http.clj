(ns shelley.server.http
  "Small helpers shared by every handler: JSON in and out, query and path
  parameters, and the one error shape the API promises.

  The contract is that nothing here ever returns HTML. A client that asks for
  JSON and gets an HTML error page cannot tell a 500 from a 404, so every
  failure — including an exception nobody caught — comes back as
  `{error: \"...\"}` with a 4xx or 5xx status."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:dynamic *json-options*
  "Cheshire options for output. Keys are emitted as written; callers use string
  or snake_case keyword keys when a shape is part of the contract."
  {:escape-non-ascii false})

(defn json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status
    :headers {"content-type" "application/json; charset=utf-8"
              "cache-control" "no-store"}
    :body (json/generate-string body *json-options*)}))

(defn error-response
  "The single error shape: `{error: msg}` plus optional extra keys."
  ([status message] (error-response status message {}))
  ([status message extra]
   (json-response status (merge {"error" (str message)} extra))))

(defn bad-request [message & [extra]] (error-response 400 message (or extra {})))
(defn unauthorized [message & [extra]] (error-response 401 message (or extra {})))
(defn forbidden [message & [extra]] (error-response 403 message (or extra {})))
(defn not-found [message & [extra]] (error-response 404 message (or extra {})))
(defn conflict [message & [extra]] (error-response 409 message (or extra {})))
(defn too-large [message & [extra]] (error-response 413 message (or extra {})))
(defn unprocessable [message & [extra]] (error-response 422 message (or extra {})))
(defn internal-error [message & [extra]] (error-response 500 message (or extra {})))
(defn unavailable [message & [extra]] (error-response 503 message (or extra {})))

(defn not-implemented
  "An endpoint the original has and this server does not, yet. A JSON 501 is
  better than a 404: a client can tell 'not built here' from 'wrong path'."
  [what]
  (error-response 501 (str what " is not implemented in clj-shelley yet")
                  {"capability" (str "unimplemented:" what)}))

;; ---------------------------------------------------------------------------
;; Request parsing

(defn body-string
  "The raw request body as a string, or nil.

  The router reads the body once into `:raw-body` because http-kit hands out a
  stream: a handler that reads it directly would consume it for everyone after
  it, which is exactly how a POST body turns into `{}`."
  [req]
  (cond
    (contains? req :raw-body) (when-let [b (:raw-body req)]
                                (String. ^bytes b "UTF-8"))
    :else (when-let [b (:body req)]
            (if (string? b)
              b
              (try (slurp b) (catch Exception _ nil))))))

(defn raw-body
  "The request body as a byte array (uploads)."
  [req]
  (when-let [b (if (contains? req :raw-body) (:raw-body req) (:body req))]
    (if (bytes? b) b
        (with-open [in (if (instance? java.io.InputStream b) b (java.io.ByteArrayInputStream. (.getBytes (str b) "UTF-8")))]
          (.readAllBytes in)))))

(defn parse-body
  "Parse a JSON request body into a map with keyword keys.

  An empty body is `{}` — several endpoints take no body at all — but a body
  that is present and malformed throws `:bad-json`, which the router turns into
  a 400 rather than pretending the client sent nothing."
  ([req] (parse-body req {}))
  ([req {:keys [default]}]
   (cond
     (contains? req :json-body) (or (:json-body req) default {})
     :else
     (let [s (body-string req)]
       (cond
         (str/blank? s) (or default {})
         :else (try
                 (let [v (json/parse-string s true)]
                   (cond
                     (map? v) v
                     ;; A client that stringifies its payload twice sends a
                     ;; JSON string containing JSON. Unwrap one level rather
                     ;; than answering "message is required" to a message.
                     (string? v) (let [inner (try (json/parse-string v true)
                                                  (catch Exception _ nil))]
                                   (if (map? inner) inner (or default {})))
                     :else (or default {})))
                 (catch Exception e
                   (throw (ex-info "invalid JSON body" {:type :bad-json} e)))))))))

(defn parse-json-object
  "Parse a JSON string that may be nil, `{}`, or malformed. Used for values the
  database stores as JSON text."
  [s]
  (cond
    (nil? s) nil
    (map? s) s
    (str/blank? (str s)) nil
    :else (try (json/parse-string (str s) true) (catch Exception _ nil))))

(defn query-params
  "Query parameters as a keyword-keyed map. http-kit parses form bodies into
  `:params`, so the query string is parsed here to keep the two apart."
  [req]
  (let [qs (:query-string req)]
    (if (str/blank? qs)
      {}
      (reduce (fn [acc pair]
                (let [[k v] (str/split pair #"=" 2)]
                  (assoc acc (keyword (java.net.URLDecoder/decode k "UTF-8"))
                         (java.net.URLDecoder/decode (or v "") "UTF-8"))))
              {}
              (remove str/blank? (str/split qs #"&"))))))

(defn param
  "A path, query, or body parameter, in that order of precedence."
  [req k]
  (or (get-in req [:params k]) (get (query-params req) k)))

(defn path-param [req k] (get-in req [:params k]))

(defn int-param
  "An integer parameter, or `default` when absent or unparsable."
  ([req k] (int-param req k nil))
  ([req k default]
   (let [v (param req k)]
     (cond
       (nil? v) default
       (number? v) (long v)
       :else (try (Long/parseLong (str v)) (catch Exception _ default))))))

(defn bool-param
  "A boolean parameter. Accepts true/false, 1/0, yes/no, on/off."
  ([req k] (bool-param req k nil))
  ([req k default]
   (let [v (param req k)]
     (cond
       (nil? v) default
       (boolean? v) v
       :else (case (str/lower-case (str v))
               ("true" "1" "yes" "on") true
               ("false" "0" "no" "off") false
               default)))))

(defn body-param
  "A key from the JSON body, falling back to the query string."
  [req body k]
  (if (contains? body k) (get body k) (param req k)))

;; ---------------------------------------------------------------------------
;; Values

(defn truthy? [v]
  (cond
    (nil? v) false
    (boolean? v) v
    (number? v) (not (zero? v))
    (string? v) (contains? #{"true" "1" "yes" "on" "t"} (str/lower-case v))
    :else true))

(defn now-iso
  "RFC 3339 UTC, seconds precision, `Z` suffix — what clients parse."
  []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'"
                                                        java.util.Locale/ROOT)
           (.atZone (java.time.Instant/now) java.time.ZoneOffset/UTC)))

(defn iso-time
  "Normalise a timestamp to RFC 3339. Accepts java.time instants, epochs in
  milliseconds, and the `YYYY-MM-DD HH:MM:SS` text SQLite stores, because
  every one of those reaches the API from somewhere."
  [v]
  (cond
    (nil? v) nil
    (instance? java.time.Instant v)
    (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'" java.util.Locale/ROOT)
             (.atZone ^java.time.Instant v java.time.ZoneOffset/UTC))
    (instance? java.time.temporal.TemporalAccessor v) (str v)
    (number? v)
    (iso-time (java.time.Instant/ofEpochMilli (long v)))
    (string? v)
    (let [s (str/trim v)]
      (if (re-matches #".*[Zz]$|.*[+-]\d{2}:?\d{2}$" s)
        s
        (try
          (let [z (java.time.LocalDateTime/parse (str/replace s " " "T"))]
            (str (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss" java.util.Locale/ROOT) z) "Z"))
          (catch Exception _ s))))
    :else (str v)))

(defn json-string
  "Encode a value the way database JSON columns reach the wire: as a JSON
  string, or nil when there is nothing to send. Clients depend on `llm_data`
  being a string, not an object."
  [v]
  (when (some? v) (json/generate-string v *json-options*)))

(defn snake-keys
  "Kebab-case keywords to snake-case strings, recursively over maps and vectors.

  Used only where the wire format is fixed by compatibility — usage payloads and
  the like — not for arbitrary content, where a caller's own key names must
  survive untouched."
  [v]
  (cond
    (map? v) (reduce-kv (fn [m k val]
                          (assoc m (str/replace (name k) "-" "_") (snake-keys val)))
                        {} v)
    (sequential? v) (mapv snake-keys v)
    :else v))

(defn compact-map
  "Drop nil-valued keys, which is what every API row wants: absent and null
  mean different things to a generated client."
  [m]
  (reduce-kv (fn [acc k v] (if (nil? v) acc (assoc acc k v))) {} m))

(defn ok [body] (json-response 200 body))
(defn created [body] (json-response 201 body))
(defn accepted [body] (json-response 202 body))
(defn no-content [] {:status 204 :headers {} :body nil})
