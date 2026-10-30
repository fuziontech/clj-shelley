(ns shelley.server
  "The HTTP surface: one router over every handler namespace.

  Route tables live with the handlers that serve them and are concatenated
  here. Two things matter about the router:

  - a pattern ending in `/` is a directory prefix, everything else must match
    segment for segment;
  - when two patterns match, the more specific one wins, so `/api/conversations/
    snapshot` cannot be swallowed by `/api/conversations/:id` no matter which
    order the tables are concatenated in.

  Every response is JSON, including the errors: an HTML error page cannot be
  read by a client, and this server is used mostly by clients."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [shelley.db :as db]
            [shelley.git :as git]
            [shelley.server.btw-api :as btw-api]
            [shelley.server.conversations :as conversations]
            [shelley.server.files-api :as files-api]
            [shelley.server.git-api :as git-api]
            [shelley.server.http :as http-util]
            [shelley.server.images :as images]
            [shelley.server.integrations :as integrations]
            [shelley.server.jobs :as jobs]
            [shelley.server.mcp :as mcp]
            [shelley.server.models :as models]
            [shelley.server.runtime :as runtime]
            [shelley.server.settings :as settings]
            [shelley.server.sse :as sse]
            [shelley.server.term-api :as term-api]
            [shelley.tools :as tools]
            [shelley.tools.bash-job :as bash-job]
            [shelley.tools.browse :as browse]
            [shelley.tools.mcp :as tool-mcp]
            [shelley.tools.message-user :as message-user]))

;; Re-exported: these names were part of this namespace before the runtime moved
;; out, and `shelley.main` and the tests still use them.
(def default-model runtime/default-model)
(def system-prompt runtime/system-prompt)
(def state runtime/state)
(def conv-state runtime/conv-state)
(def emit-event! runtime/emit-event!)
(def run-in-background runtime/run-in-background)
(def run-agent-turn runtime/run-agent-turn)

;; ---------------------------------------------------------------------------
;; Version and health

(def capabilities
  ["thinking-levels" "queued-messages" "compaction" "background-jobs"
   "mcp" "subagents" "search" "tags" "terminal" "git" "images" "fork"
   "stream2-patches" "profiles" "feature-flags" "integrations"])

(defn version [_]
  (http-util/ok {"tag" "0.1.0-clj"
                 "commit" (or (:commit (try (git/head (System/getProperty "user.dir"))
                                            (catch Exception _ nil)))
                              "unknown")
                 "capabilities" capabilities}))

(defn version-check
  "Whether a newer build is available. This server is not distributed as a
  self-updating binary, so the honest answer is 'this is the current version'."
  [_]
  (let [v (-> (version nil) :body (json/parse-string true))]
    (http-util/ok {"current_tag" (:tag v)
                   "latest_tag" (:tag v)
                   "update_available" false
                   "disabled" true
                   "reason" "clj-shelley is not self-updating"
                   "checked_at" (http-util/now-iso)})))

(defn version-changelog
  "The original lists commits between two tags from GitHub. There is no release
  channel here, so the list is empty rather than fabricated."
  [req]
  (let [{:keys [current latest]} (http-util/query-params req)]
    (if (or (str/blank? (str current)) (str/blank? (str latest)))
      (http-util/bad-request "current and latest query parameters are required")
      (http-util/ok []))))

(defn health [_]
  (http-util/ok {"status" "ok"
                 "model" (models/default-model-id)
                 "capabilities" capabilities}))

;; ---------------------------------------------------------------------------
;; Debug pages. The original has a family of these; what a developer needs from
;; them here is (a) that the route exists and (b) something true to look at.

(defn- html [body]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body body})

(defn debug-index [_]
  (html (str "<!doctype html><title>clj-shelley debug</title>"
             "<h1>clj-shelley debug</h1><ul>"
             "<li><a href=\"/debug/conversation-stream\">conversation stream</a></li>"
             "<li><a href=\"/debug/conversation-stream/history\">patch history (JSON)</a></li>"
             "<li><a href=\"/debug/histograms\">histograms (JSON)</a></li>"
             "<li><a href=\"/debug/mcp\">mcp servers (JSaON)</a></li>"
             "<li><a href=\"/debug/loremipsum\">lorem ipsum</a></li>"
             "<li><a href=\"/debug/stylebook\">stylebook</a></li>"
             "</ul>")))

(defn debug-conversation-stream [_]
  (html (str "<!doctype html><title>conversation stream</title>"
             "<h1>Conversation list patch stream</h1>"
             "<p>Every patch this server has published, newest last.</p>"
             "<pre id=\"out\">loading…</pre>"
             "<script>fetch('/debug/conversation-stream/history').then(r=>r.json())"
             ".then(d=>{document.getElementById('out').textContent="
             "JSON.stringify(d.events||d,null,2)})</script>")))

(defn debug-conversation-stream-history [req]
  (let [since (http-util/param req :since)
        events (if since (sse/patch-history since) (sse/patch-history))]
    (http-util/ok {"events" events "count" (count events)})))

(defn debug-histograms [_]
  (http-util/ok {"histograms" {}
                 "note" "latency histograms are not recorded in this build"}))

(defn debug-loremipsum [req]
  (http-util/ok {"text" (str/join " " (repeat 40 "lorem ipsum dolor sit amet"))
                 "pending" (when (= :post (:request-method req)) true)}))

(defn debug-stylebook [_]
  (html "<!doctype html><title>stylebook</title><h1>stylebook</h1><p>See <code>/ui/app.css</code> for the component styles.</p>"))

(defn debug-mcp [_] (mcp/handler-list nil))

(defn debug-pprof
  "Go's profiler endpoints. The JVM has no pprof; a clear 404 beats a hang."
  [_]
  (http-util/not-found "pprof is not available: this server runs on the JVM"))

(defn debug-channel-gateway [_]
  (http-util/not-implemented "channel gateway"))

(defn upgrade [_] (http-util/not-implemented "self-upgrade"))

(defn exit
  "The original exits so systemd restarts it. This server has no supervisor and
  exiting would be indistinguishable from a crash, so the request is recorded
  and refused."
  [_]
  (http-util/ok {"status" "not_supported"
                 "message" "clj-shelley does not exit on request"}))

;; ---------------------------------------------------------------------------
;; Artefacts and the single-page UI

(defn index [_]
  (html (slurp (io/resource "index.html"))))

(defn get-iframe [req]
  (if-let [row (db/get-iframe (http-util/path-param req :id))]
    (html (:html row))
    (http-util/not-found "no such artefact")))

(defn iframe-file
  "Fallback: iframes the tool wrote to disk rather than to the database."
  [req]
  (let [f (io/file tools/iframe-dir (str (http-util/path-param req :id) ".html"))]
    (if (.exists f) (html (slurp f)) (http-util/not-found "no such artefact"))))

;; ---------------------------------------------------------------------------
;; Route table

(def core-routes
  [["/" {:get index}]
   ["/version" {:get version}]
   ["/version-check" {:get version-check}]
   ["/version-changelog" {:get version-changelog}]
   ["/api/version" {:get version}]
   ["/api/health" {:get health}]
   ["/upgrade" {:post upgrade}]
   ["/upgrade-headless-shell" {:post upgrade}]
   ["/exit" {:post exit}]
   ["/debug/" {:get debug-index}]
   ["/debug/conversation-stream" {:get debug-conversation-stream}]
   ["/debug/conversation-stream/history" {:get debug-conversation-stream-history}]
   ["/debug/histograms" {:get debug-histograms}]
   ["/debug/loremipsum" {:get debug-loremipsum :post debug-loremipsum}]
   ["/debug/stylebook" {:get debug-stylebook}]
   ["/debug/mcp" {:get debug-mcp}]
   ["/debug/pprof/" {:get debug-pprof}]
   ["/debug/pprof/cmdline" {:get debug-pprof}]
   ["/debug/pprof/profile" {:get debug-pprof}]
   ["/debug/pprof/symbol" {:get debug-pprof}]
   ["/debug/pprof/trace" {:get debug-pprof}]
   ["/debug/channels/gateway/:id/:id/:id" {:post debug-channel-gateway
                                                   :delete debug-channel-gateway}]
   ["/iframe/:id" {:get iframe-file}]
   ["/api/iframes/:id" {:get get-iframe}]])

(def routes
  "Every routable path: core, conversations, streams, models, settings, MCP,
  integrations, jobs, images, and the modules that bring their own tables."
  (vec (concat core-routes
               conversations/routes
               btw-api/routes
               sse/routes
               models/routes
               settings/routes
               mcp/routes
               integrations/routes
               jobs/routes
               images/routes
               files-api/routes
               git-api/routes
               term-api/routes)))

;; ---------------------------------------------------------------------------
;; Static UI assets

(defn- static-uri? [uri] (str/starts-with? (str uri) "/ui/"))

(def ^:private mime-types
  {"js" "application/javascript" "css" "text/css" "html" "text/html; charset=utf-8"
   "json" "application/json" "svg" "image/svg+xml" "woff2" "font/woff2"
   "png" "image/png" "ico" "image/x-icon" "map" "application/json"})

(defn- serve-static [uri]
  (let [rel (subs uri 4)
        f (io/file "resources/ui" rel)]
    (if (and (.isFile f) (not (str/includes? rel "..")))
      {:status 200
       :headers {"content-type" (or (get mime-types (last (str/split (str rel) #"\.")))
                                    "application/octet-stream")}
       :body (slurp f)}
      (http-util/not-found "no such asset"))))

;; ---------------------------------------------------------------------------
;; Router

(defn- segments [path]
  (->> (str/split (str/replace (first (str/split (str path) #"\?")) #"^/" "") #"/")
       (remove str/blank?)
       vec))

(defn- prefix-pattern? [pattern]
  (and (str/ends-with? pattern "/") (not= pattern "/")))

(defn- match-one
  "Match one pattern against one path. Returns a params map or nil."
  [pattern-segs uri-segs]
  (when (= (count pattern-segs) (count uri-segs))
    (reduce (fn [params [p u]]
              (cond
                (nil? params) nil
                (str/starts-with? p ":")
                ;; A path may name two segments `:id` — the original does for
                ;; e.g. `.../background-jobs/{jobID}/kill`. The second becomes
                ;; `:id-2` so the handler can tell them apart.
                (let [k (keyword (subs p 1))
                      k (if (contains? params k) (keyword (str (subs p 1) "-2")) k)]
                  (assoc params k u))
                (= p u) params
                :else nil))
            {}
            (map vector pattern-segs uri-segs))))

(defn- match-pattern
  "Params for one route pattern against a request path, or nil."
  [pattern uri]
  (let [path (first (str/split (str uri) #"\?"))]
    (if (prefix-pattern? pattern)
      (when (str/starts-with? path pattern) {})
      (match-one (segments pattern) (segments path)))))

(defn- specificity
  "Lower is more specific: literals beat parameters, exact beats prefix."
  [pattern]
  (+ (* 10 (count (filter #(str/starts-with? % ":") (segments pattern))))
     (if (prefix-pattern? pattern) 5 0)))

(defn- match-route
  "The private router entry point. Returns `[handler params]` or nil."
  [method uri]
  (let [verb (keyword (str/lower-case (name method)))]
    (->> routes
         (keep-indexed (fn [i [pattern methods]]
                         (when-let [h (get methods verb)]
                           (when-some [params (match-pattern pattern uri)]
                             {:order i :specificity (specificity pattern)
                              :handler h :params params}))))
         (sort-by (juxt :specificity :order))
         first
         ((fn [m] (when m [(:handler m) (:params m)]))))))

(defn- wants-body? [req]
  (and (:body req)
       (not (string? (:body req)))
       (not (:websocket? req))
       (contains? #{:post :put :patch :delete} (:request-method req))))

(defn- read-body-once
  "Read the request body into bytes exactly once.

  http-kit gives handlers a stream, so whoever reads first wins. Reading here
  means every handler sees the same body, and a malformed one becomes a 400
  before any handler runs."
  [req]
  (if (wants-body? req)
    (try
      (assoc req :raw-body (.readAllBytes ^java.io.InputStream (:body req)))
      (catch Exception _ req))
    req))

(defn- parse-body-or-error
  "The parsed JSON body, or `{}` when there is none. Malformed JSON is a 400:
  pretending the client sent nothing hides the bug on their side."
  [req]
  (if (and (:raw-body req) (seq (http-util/body-string req)))
    (try
      (http-util/parse-body req)
      (catch clojure.lang.ExceptionInfo e
        (throw (ex-info "invalid JSON body" {:type :bad-request :status 400}))))
    {}))

(defn handler [req]
  (try
    (cond
      (static-uri? (:uri req)) (serve-static (:uri req))
      :else
      (if-let [[h params] (match-route (:request-method req) (:uri req))]
        (let [req (read-body-once req)
              ;; Handlers read `:params`. http-kit only puts path captures and
              ;; form fields there, so a query string would be invisible to
              ;; every `?cwd=`, `?path=` and `?q=` the UI sends. Path captures
              ;; win over query parameters of the same name.
              req (assoc req :params (merge (http-util/query-params req)
                                            (:params req)
                                            params))
              req (assoc req :json-body (parse-body-or-error req))]
          (h req))
        (http-util/not-found (str "no route for " (name (:request-method req))
                                  " " (:uri req)))))
    (catch clojure.lang.ExceptionInfo e
      (let [status (or (:status (ex-data e))
                       (case (:type (ex-data e))
                         :bad-request 400
                         :bad-json 400
                         :too-large 413
                         500))]
        (if (= 500 status)
          (do (binding [*out* *err*]
                (println "request failed:" (.getMessage e))
                (.printStackTrace e))
              (http-util/internal-error (.getMessage e)))
          (http-util/error-response status (.getMessage e)))))
    (catch Throwable t
      (binding [*out* *err*]
        (println "request failed:" (.getMessage t))
        (.printStackTrace t))
      (http-util/internal-error (str (.getMessage t))))))

;; ---------------------------------------------------------------------------
;; Start

(declare shutdown-children!)

(defonce ^:private server-instance (atom nil))

(defn start!
  ([] (start! (Integer/parseInt (or (System/getenv "PORT") "8000"))))
  ([port]
   (db/init!)
   ;; Custom models reach their own endpoints with their own keys, so llm needs
   ;; to know about them before the first turn runs.
   (models/reload-model-routes!)
   ;; One hook for the whole process: SIGTERM from a restart must not leave
   ;; Chrome and MCP children behind.
   (.addShutdownHook (Runtime/getRuntime)
                     (Thread. ^Runnable (fn [] (shutdown-children!)) "clj-shelley-shutdown"))
   (reset! server-instance
           (http/run-server handler {:port port
                                     :legacy-return-value? false
                                     :ip "0.0.0.0"
                                     :thread 32
                                     :queue-size 1024}))))

(defn- kill-running-bash-jobs!
  "Background bash jobs are process groups; they do not stop when the JVM does."
  []
  (doseq [{:keys [job-id running]} (bash-job/list-jobs)]
    (when (and running (bash-job/find-job job-id))
      (try (bash-job/kill! (bash-job/find-job job-id)) (catch Throwable _ nil)))))

(defn shutdown-children!
  "Release everything that holds an OS process open.

  A headless Chrome and every MCP child server outlive the JVM otherwise, and a
  developer who restarts the service ten times ends up with ten browsers. A
  prompt still waiting for an answer is released too, so the waiting tool thread
  cannot hold the VM open."
  []
  (doseq [[label f] [["browser" browse/close!]
                     ["mcp" tool-mcp/disconnect-all!]
                     ["message-user" message-user/reset-questions!]
                     ["bash-jobs" kill-running-bash-jobs!]]]
    (try
      (f)
      (catch Throwable t
        (binding [*out* *err*]
          (println "shutdown:" label "failed:" (.getMessage t)))))))

(defn stop!
  "Stop the HTTP server and release every child process. Safe to call twice."
  []
  (when-let [srv @server-instance]
    (http/server-stop! srv {:timeout 200})
    (reset! server-instance nil))
  (shutdown-children!))

(defn port
  "The port actually bound, which matters when start! was given 0."
  []
  (when-let [srv @server-instance]
    (http/server-port srv)))

