(ns shelley.tools.mcp
  "MCP client: the agent's window into other people's tools.

  Two transports, both JSON-RPC 2.0:

  - **stdio**: the server is a child process; requests and responses are one
    JSON object per line on its stdin/stdout. Newline framing, not the
    Content-Length framing LSP uses — a common way to get this wrong.
  - **streamable HTTP**: each request is a POST; the response is either a JSON
    body or an SSE stream of JSON-RPC messages. The server may hand back an
    `Mcp-Session-Id` header that every later request must echo.

  Tools are namespaced as `SERVER.TOOL`, the same shape the original uses, so
  a server named `files` exposing `read` becomes the agent tool `files.read`.
  Splitting on the first dot is safe because server names cannot contain dots.

  Sessions connect lazily and stay open. `tool-defs` never blocks on a
  connection: it returns what is cached and kicks off a connect in the
  background, so a dead MCP server costs the agent a missing tool rather than
  a hung turn.

  Configuration, merged with the project file winning:

    ~/.clj-shelley/mcp.json
    <working-dir>/.shelley/mcp.json

    {\"servers\": {\"files\": {\"command\": \"npx\", \"args\": [\"-y\", \"...\"],
                                \"env\": {\"TOKEN\": \"...\"}},
                    \"search\": {\"url\": \"https://example.com/mcp\",
                                 \"headers\": {\"Authorization\": \"Bearer ...\"}}}}"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent TimeUnit]))

(def protocol-version "2025-06-18")
(def default-timeout-ms 30000)

;; ---------------------------------------------------------------------------
;; Configuration

(defn- parse-json-file [f]
  (when (.exists (io/file f))
    (try
      (json/parse-string (slurp f) true)
      (catch Exception e
        (log/warn e "ignoring unreadable MCP config" (str f))
        nil))))

(defn config-files [working-dir]
  [(io/file (System/getProperty "user.home") ".clj-shelley" "mcp.json")
   (io/file (str working-dir) ".shelley" "mcp.json")])

(defn load-config
  "Configured MCP servers by name: {:command :args :env} or {:url :headers}.
  A later file overrides an earlier one per server name."
  ([] (load-config (System/getProperty "user.dir")))
  ([working-dir]
   (reduce (fn [acc f]
             (if-let [cfg (parse-json-file f)]
               (merge acc (or (:servers cfg) cfg))
               acc))
           {}
           (config-files working-dir))))

;; ---------------------------------------------------------------------------
;; Sessions

(defonce ^:private sessions (atom {}))   ; server name -> session
(defonce ^:private tool-cache (atom {})) ; "server.tool" -> tool def
(defonce ^:private connecting (atom #{}))

(defn session-for
  "The live session for `name`, or nil. Public so a server can health-check
  and so tests can drive the client without reaching into a private atom."
  [name]
  (get @sessions name))

(defn- new-session
  "A session is a plain map of atoms: every mutable part is small and
  independent, so one lock is not worth the contention the reader thread would
  cause."
  [name cfg]
  {:name name
   :config cfg
   :next-id (atom 0)
   :pending (atom {})
   :notifications (atom [])
   :closed? (atom false)
   :server-info (atom nil)
   :session-id (atom nil)
   :proc (atom nil)
   :writer (atom nil)
   :client (atom nil)})

(defn- send-line! [^BufferedWriter w obj]
  (locking w
    (.write w ^String (json/generate-string obj))
    (.write w "\n")
    (.flush w)))

(defn- dispatch!
  "Route one incoming JSON-RPC message: a response resolves its promise; a
  notification is kept for whoever wants it."
  [session msg]
  (cond
    (contains? msg :id)
    (when-let [p (get @(:pending session) (:id msg))]
      (swap! (:pending session) dissoc (:id msg))
      (deliver p msg))

    :else (swap! (:notifications session) (fn [v] (take-last 100 (conj v msg))))))

(defn- start-stdio-reader! [session ^BufferedReader r]
  (doto (Thread.
         ^Runnable
         (fn []
           (try
             (loop []
               (when-let [line (.readLine r)]
                 (when-not (str/blank? line)
                   (try (dispatch! session (json/parse-string line true))
                        (catch Exception e
                          (log/warn e "unparseable MCP message"))))
                 (recur)))
             (catch Exception e
               (log/debug e "MCP stdio reader ended")))
           ;; Every outstanding request must fail rather than hang.
           (reset! (:closed? session) true)
           (doseq [[id p] @(:pending session)]
             (deliver p {:error {:code -32000 :message "MCP server disconnected"}})
             (swap! (:pending session) dissoc id))))
    (.setDaemon true)
    (.setName (str "mcp-reader-" (:name session)))
    (.start)))

(defn- drain-stderr!
  "MCP servers log to stderr. Swallow it into the debug log so a server that
  dies at startup leaves a trace instead of blocking on a full pipe."
  [session ^Process proc]
  (doto (Thread. ^Runnable
                 (fn []
                   (try
                     (with-open [r (io/reader (.getErrorStream proc))]
                       (doseq [line (line-seq r)]
                         (log/debug "mcp" (:name session) line)))
                     (catch Exception _ nil))))
    (.setDaemon true)
    (.setName (str "mcp-stderr-" (:name session)))
    (.start)))

(defn- start-stdio! [session]
  (let [{:keys [command args env]} (:config session)
        argv (into [command] (or args []))
        pb (doto (ProcessBuilder. (vec argv))
             (.redirectErrorStream false))
        _ (doseq [[k v] env] (.put (.environment pb) (name k) (str v)))
        proc (.start pb)]
    (reset! (:proc session) proc)
    (reset! (:writer session) (BufferedWriter. (OutputStreamWriter. (.getOutputStream proc) "UTF-8")))
    (drain-stderr! session proc)
    (start-stdio-reader! session (BufferedReader. (InputStreamReader. (.getInputStream proc) "UTF-8")))
    session))

(defn- http-post!
  "POST one JSON-RPC message over streamable HTTP. Returns the JSON-RPC
  message (from a JSON body or the first SSE data event)."
  [session body]
  (let [client (or @(:client session)
                   (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofSeconds 15))
                       (.build)))
        url (:url (:config session))
        builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis default-timeout-ms))
                    (.header "content-type" "application/json")
                    (.header "accept" "application/json, text/event-stream")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString (json/generate-string body))))
        builder (reduce (fn [b [k v]] (.header b (name k) (str v)))
                        builder (or (:headers (:config session)) {}))
        builder (if-let [sid @(:session-id session)]
                  (.header builder "mcp-session-id" sid)
                  builder)
        resp (.send client (.build builder) (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (reset! (:client session) client)
    (when-let [sid (-> resp .headers (.firstValue "mcp-session-id"))]
      (when-not (str/blank? sid) (reset! (:session-id session) sid)))
    (let [status (.statusCode resp)
          body-text (.body resp)
          ctype (-> resp .headers (.firstValue "content-type") (or ""))]
      (cond
        (>= status 400)
        {:error {:code status :message (str "HTTP " status ": " (subs body-text 0 (min 400 (count body-text))))}}

        (str/includes? ctype "text/event-stream")
        (or (->> (str/split-lines body-text)
                 (keep #(when (str/starts-with? % "data:") (str/trim (subs % 5))))
                 (keep (fn [payload]
                         (try (json/parse-string payload true) (catch Exception _ nil))))
                 (filter #(contains? % :result))
                 first)
            {:error {:code -32000 :message (str "no response message in SSE body: "
                                                 (subs body-text 0 (min 400 (count body-text))))}})

        (str/blank? body-text)
        {:result {}}

        :else
        (try (json/parse-string body-text true)
             (catch Exception _ {:error {:code -32700 :message "unparseable JSON response"}}))))))

(defn request!
  "Send a JSON-RPC request and wait for its response. Throws ex-info when the
  server returns an error or the timeout passes — a silent nil here would be
  indistinguishable from a tool that returned nothing."
  ([session method params] (request! session method params default-timeout-ms))
  ([session method params timeout-ms]
   (let [id (swap! (:next-id session) inc)
         msg (cond-> {:jsonrpc "2.0" :id id :method method}
               params (assoc :params params))]
     (if @(:writer session)
       (let [p (promise)]
         (swap! (:pending session) assoc id p)
         (send-line! @(:writer session) msg)
         (let [resp (deref p timeout-ms ::timeout)]
           (swap! (:pending session) dissoc id)
           (cond
             (= ::timeout resp)
             (throw (ex-info (str "MCP server " (:name session) " did not answer " method
                                  " within " timeout-ms "ms")
                             {:kind :mcp-timeout :server (:name session) :method method}))
             (:error resp)
             (throw (ex-info (str (get-in resp [:error :message] "MCP error")
                                  " (" method " on " (:name session) ")")
                             {:kind :mcp-error :server (:name session) :method method
                              :code (get-in resp [:error :code])}))
             :else (:result resp))))
       ;; HTTP is synchronous: the response *is* the reply.
       (let [resp (http-post! session msg)]
         (if (:error resp)
           (throw (ex-info (str (get-in resp [:error :message] "MCP error")
                                " (" method " on " (:name session) ")")
                           {:kind :mcp-error :server (:name session) :method method}))
           (:result resp)))))))

(defn notify!
  "Send a JSON-RPC notification (no id, no response)."
  [session method params]
  (let [msg (cond-> {:jsonrpc "2.0" :method method}
              params (assoc :params params))]
    (if @(:writer session)
      (send-line! @(:writer session) msg)
      (try (http-post! session msg) (catch Exception e
                                      (log/debug e "MCP notification failed"))))))

;; ---------------------------------------------------------------------------
;; Session lifecycle

(defn- initialize! [session]
  (let [result (request! session "initialize"
                         {:protocolVersion protocol-version
                          :capabilities {}
                          :clientInfo {:name "clj-shelley" :version "1"}})]
    (reset! (:server-info session) result)
    (notify! session "notifications/initialized" nil)
    result))

(defn connect!
  "Open a session to `name` from its config. Idempotent: an existing live
  session is returned as-is."
  [name cfg]
  (if-let [s (get @sessions name)]
    (if @(:closed? s)
      (do (swap! sessions dissoc name) (connect! name cfg))
      s)
    (let [session (new-session name cfg)
          session (if (:url cfg)
                    session
                    (start-stdio! session))]
      (swap! sessions assoc name session)
      (try
        (initialize! session)
        session
        (catch Exception e
          (swap! sessions dissoc name)
          (when-let [^Process p @(:proc session)] (try (.destroyForcibly p) (catch Exception _)))
          (throw (ex-info (str "could not connect to MCP server " name ": " (.getMessage e))
                          {:kind :mcp-connect :server name})))))))

(defn disconnect! [name]
  (when-let [s (get @sessions name)]
    (when-let [^Process p @(:proc s)] (try (.destroyForcibly p) (catch Exception _)))
    (swap! sessions dissoc name)
    (swap! tool-cache (fn [m] (into {} (remove (fn [[k _]] (str/starts-with? k (str name "."))) m))))))

(defn disconnect-all! []
  (doseq [n (keys @sessions)] (disconnect! n)))

;; ---------------------------------------------------------------------------
;; Tools

(defn- mcp-content->blocks [content]
  (vec (keep (fn [c]
               (case (:type c)
                 "text" {:type :text :text (str (:text c))}
                 "image" {:type :image
                           :media-type (:mimeType c)
                           :data (:data c)}
                 "resource" {:type :text
                             :text (str "[resource " (or (get-in c [:resource :uri]) "?") "]\n"
                                        (or (get-in c [:resource :text]) ""))}
                 {:type :text :text (str "[" (:type c) "]")}))
             content)))

(declare call-tool!)

(defn- tool-def [server-name tool]
  {:name (str server-name "." (:name tool))
   :description (or (:description tool) (str "MCP tool " (:name tool) " from " server-name))
   :default-on? true
   :input-schema (or (:inputSchema tool) {:type "object" :properties {}})
   :handler (fn [_ctx args] (call-tool! (str server-name "." (:name tool)) args))
   :mcp {:server server-name :tool (:name tool)}})

(defn list-tools!
  "Every tool a session exposes, following pagination cursors."
  [session]
  (loop [cursor nil out []]
    (let [res (request! session "tools/list" (if cursor {:cursor cursor} nil))
          tools (:tools res)
          out (into out (map #(tool-def (:name session) %)) tools)]
      (if-let [next (:nextCursor res)]
        (recur next out)
        out))))

(defn refresh!
  "(Re)connect every configured server and cache its tools. Returns
  {server {:ok n-tools} | {:error message}}. Called at startup and by the UI."
  ([] (refresh! (System/getProperty "user.dir")))
  ([working-dir]
   (reduce (fn [acc [name cfg]]
             (assoc acc name
                    (try
                      (let [session (connect! name cfg)
                            tools (list-tools! session)]
                        (swap! tool-cache into (map (juxt :name identity) tools))
                        {:ok (count tools)})
                      (catch Exception e
                        (log/warn e "MCP server did not start" name)
                        {:error (.getMessage e)}))))
           {}
           (load-config working-dir))))

(defn ensure-tools!
  "Make sure every configured server is connected and its tools cached,
  connecting at most once per server name. Never throws; returns the cached
  tool definitions."
  ([] (ensure-tools! (System/getProperty "user.dir")))
  ([working-dir]
   (let [cfg (load-config working-dir)]
     (doseq [[name c] cfg]
       (when (and (not (contains? @ connecting name))
                  (not (contains? @sessions name)))
         (swap! connecting conj name)
         (future
           (try
             (let [session (connect! name c)
                   tools (list-tools! session)]
               (swap! tool-cache into (map (juxt :name identity) tools)))
             (catch Exception e
               (log/warn e "MCP server did not start" name))
             (finally (swap! connecting disj name))))))
     (vec (vals @tool-cache)))))

(defn tool-defs
  "Cached MCP tool definitions, in registry shape. Starts connections in the
  background on first call and never blocks."
  ([] (tool-defs (System/getProperty "user.dir")))
  ([working-dir]
   (if (empty? (load-config working-dir))
     []
     (ensure-tools! working-dir))))

(defn parse-tool-name [name]
  (let [i (str/index-of name ".")]
    (when (and i (pos? i))
      {:server (subs name 0 i) :tool (subs name (inc i))})))

(defn call-tool!
  "Call SERVER.TOOL. A tool-level failure is a result with :is-error set, not
  an exception: the model should see what the tool said."
  [name args]
  (let [{:keys [server tool]} (parse-tool-name name)]
    (when-not server
      (throw (ex-info (str "not an MCP tool name: " name) {:kind :bad-args})))
    (let [session (or (get @sessions server)
                      (when-let [cfg (get (load-config) server)] (connect! server cfg)))]
      (when-not session
        (throw (ex-info (str "MCP server " server " is not configured") {:kind :mcp-unknown}))))
    (let [session (get @sessions server)
          res (request! session "tools/call" {:name tool :arguments (or args {})})]
      {:is-error (boolean (:isError res))
       :content (let [blocks (mcp-content->blocks (or (:content res) []))]
                  (if (seq blocks) blocks [{:type :text :text "(no content)"}]))
       :mcp {:server server :tool tool :structured (:structuredContent res)}})))

(defn session-status
  "What the UI needs to draw an MCP page: configured servers and their state."
  [working-dir]
  (let [cfg (load-config working-dir)]
    (mapv (fn [[name c]]
            {:name name
             :transport (if (:url c) "http" "stdio")
             :target (or (:url c) (str/join " " (cons (:command c) (:args c))))
             :connected (some? (get @sessions name))
             :tools (count (filter #(str/starts-with? % (str name ".")) (keys @tool-cache)))})
          cfg)))
