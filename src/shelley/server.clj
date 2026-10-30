(ns shelley.server
  "HTTP surface: a small JSON API, an SSE event stream per conversation, and one
  static page. No framework — the whole UI is a single file that speaks JSON
  and EventSource."
  (:require [shelley.compact :as compact]
            [shelley.server.files-api :as files-api]
            [shelley.server.git-api :as git-api]
            [shelley.server.term-api :as term-api]
            [shelley.usage :as usage]
            [shelley.git :as git]
            [org.httpkit.server :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [shelley.db :as db]
            [shelley.loop :as loop]
            [shelley.tools :as tools]
            [shelley.llm :as llm]))

(def default-model
  (or (System/getenv "CLJ_SHELLEY_MODEL") "anthropic/claude-sonnet-5-5"))

(def system-prompt
  (str "You are Shelley, a coding agent running on a Linux VM. You and the user share one "
       "working directory.\n\n"
       "Work by acting, not by describing. When asked to do something to the filesystem or a "
       "repository, use bash and patch. Read before you edit. Prefer several small correct "
       "commands over one clever line. When a command fails, read the error and fix the cause "
       "rather than retrying the same thing.\n\n"
       "Report what you actually did and what you verified. If you could not verify something, "
       "say so plainly. Keep chat replies short — the work is the message."))

(defonce state
  (atom {:conversations {}}))

(defn conv-state [id]
  (or (get-in @state [:conversations id])
      (let [fresh {:cwd (atom (or (:working_dir (db/get-conversation id))
                                  (System/getProperty "user.dir")))
                   :cancelled? (atom false)
                   :running? (atom false)
                   :queue (atom [])
                   :subs (atom #{})}]
        (swap! state assoc-in [:conversations id] fresh)
        fresh)))

;; ---------------------------------------------------------------------------
;; Event fan-out

(declare emit-event!)

(defn run-in-background
  "Run a turn off the request thread. A future that throws would otherwise
  vanish silently, so log it and surface it to any open stream."
  [conv-id f]
  (future
    (try (f)
         (catch Throwable t
           (binding [*out* *err*] (println "background turn failed:" (.getMessage t)))
           (.printStackTrace t)
           (emit-event! conv-id {:type :error :text (str "turn failed: " (.getMessage t))})
           (emit-event! conv-id {:type :turn-end :status :error})))))

(defn emit-event! [conv-id event]
  (let [payload (str "data: " (json/generate-string (assoc event :conversation-id conv-id)) "\n\n")]
    (doseq [ch @(:subs (conv-state conv-id))]
      (http/send! ch payload false))))

;; ---------------------------------------------------------------------------
;; Running a turn

(declare run-agent-turn last-assistant-text)

(defn run-subagent
  "Create a child conversation, run one turn to completion, return its text."
  [{:keys [parent-id slug prompt toolset model]}]
  (let [conv (db/create-conversation! {:slug slug
                                       :title (str "subagent: " slug)
                                       :model (or model default-model)
                                       :working-dir (:working_dir (db/get-conversation parent-id))})
        _result (run-agent-turn {:conversation conv
                                 :text prompt
                                 :toolset (or toolset :default)})]
    {:slug slug
     :text (last-assistant-text (db/messages (:id conv)))}))

(defn- last-assistant-text [messages]
  (->> messages
       (filter #(= :assistant (:role %)))
       last
       :content
       (filter llm/text?)
       (map :text)
       (str/join "\n")))

(defn run-agent-turn
  "Run one turn for a conversation. `:text` is the user message, or nil to
  resume from history. Blocks until the turn ends."
  [{:keys [conversation text toolset record?]}]
  (let [conv-id (:id conversation)
        st (conv-state conv-id)
        _ (reset! (:cancelled? st) false)
        _ (reset! (:running? st) true)
        _ (when text
            (let [m (llm/user-message text)]
              (when (not= false record?) (db/append-message! {:conversation-id conv-id
                                                              :role :user
                                                              :content (:content m)}))))
        history (db/messages conv-id)
        ctx {:working-dir (:cwd st)
             :conversation-id conv-id
             :emit (fn [e] (emit-event! conv-id e))
             :base-url (llm/base-url)
             :run-subagent run-subagent}
        model (or (:model conversation) default-model)
        result (try
                 (loop/run-turn {:model model
                                 :system [system-prompt
                                          (str "Working directory: " @(:cwd st))]
                                 :messages history
                                 :tools (tools/tool-defs toolset)
                                 :ctx ctx
                                 :emit (:emit ctx)
                                 :thinking-level (:reasoning_effort conversation)
                                 :context-limit 200000
                                 :compact-fn (fn [msgs]
                                               (compact/compact-history msgs {:model model}))
                                 :cancelled? #(boolean @(:cancelled? st))
                                 :queue (:queue st)
                                 :record! (fn [m]
                                            (db/append-message! {:conversation-id conv-id
                                                                 :role (:role m)
                                                                 :content (:content m)
                                                                 :usage (:usage m)}))
                                 :record-error! (fn [m]
                                                  (db/append-message! {:conversation-id conv-id
                                                                       :role :assistant
                                                                       :content (:content m)
                                                                       :error (:error m)}))})
                 (finally (reset! (:running? st) false)))]
    (when (and text (nil? (:title conversation)))
      (db/touch! conv-id (subs text 0 (min 80 (count text)))))
    (when (not= false record?)
      (db/touch! conv-id nil))
    result))

;; ---------------------------------------------------------------------------
;; Handlers

(defn json-response [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/generate-string body)})

(defn- parse-body [req]
  (if-let [b (:body req)]
    (json/parse-string (slurp b) true)
    {}))

(defn index [_]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body (slurp (io/resource "index.html"))})

(defn list-conversations [_]
  (json-response 200 {:conversations (mapv #(select-keys % [:id :slug :title :model :updated_at])
                                           (db/list-conversations))}))

(defn create-conversation [req]
  (let [{:keys [message model]} (parse-body req)
        conv (db/create-conversation! {:title (when message (subs message 0 (min 80 (count message))))
                                       :model (or model default-model)})]
    (when message
      (run-in-background (:id conv) #(run-agent-turn {:conversation conv :text message})))
    (json-response 200 {:conversation conv})))

(defn get-conversation [req]
  (let [id (:id (:params req))
        conv (db/get-conversation id)]
    (if conv
      (json-response 200 {:conversation conv
                          :messages (db/messages id)
                          :running (boolean @(:running? (conv-state id)))})
      (json-response 404 {:error "not found"}))))

(defn post-message [req]
  (let [id (:id (:params req))
        conv (db/get-conversation id)
        {:keys [text]} (parse-body req)]
    (if-not conv
      (json-response 404 {:error "not found"})
      (let [st (conv-state id)]
        (if @(:running? st)
          ;; The turn is already running: splice this in before its next
          ;; request rather than refusing it.
          (do (swap! (:queue st) conj text)
              (emit-event! id {:type :queued :text text})
              (json-response 202 {:status "queued"}))
          (do (run-in-background id #(run-agent-turn {:conversation conv :text text}))
              (json-response 202 {:status "accepted"})))))))

(defn cancel [req]
  (let [id (:id (:params req))]
    (reset! (:cancelled? (conv-state id)) true)
    (json-response 200 {:status "cancelling"})))

(defn events
  "Server-sent events for one conversation. The UI opens this once per
  conversation and gets every token and tool call as it happens."
  [req]
  (let [id (:id (:params req))
        st (conv-state id)]
    (http/as-channel req
      {:on-open (fn [ch]
                  (swap! (:subs st) conj ch)
                  ;; EventSource requires this content type; without it the
                  ;; browser silently refuses the stream.
                  (http/send! ch {:status 200
                                  :headers {"content-type" "text/event-stream"
                                            "cache-control" "no-cache"
                                            "connection" "keep-alive"
                                            "x-accel-buffering" "no"}}
                              false)
                  (http/send! ch ": connected\n\n" false))
       :on-close (fn [ch _] (swap! (:subs st) disj ch))})))

(defn get-iframe [req]
  (if-let [row (db/get-iframe (:id (:params req)))]
    {:status 200 :headers {"content-type" "text/html; charset=utf-8"} :body (:html row)}
    {:status 404 :body "not found"}))

(defn iframe-file
  "Fallback: iframes written to disk by the output_iframe tool."
  [req]
  (let [id (:id (:params req))
        f (io/file tools/iframe-dir (str id ".html"))]
    (if (.exists f)
      {:status 200 :headers {"content-type" "text/html; charset=utf-8"} :body (slurp f)}
      {:status 404 :body "not found"})))

(defn health [_] (json-response 200 {:status "ok" :model default-model}))

(def capabilities
  ["thinking-levels" "queued-messages" "compaction" "background-jobs"
   "mcp" "subagents" "search" "tags" "terminal" "git"])

(defn version [_]
  (json-response 200 {:tag "0.1.0-clj"
                      :commit (or (:commit (git/head (System/getProperty "user.dir"))) "unknown")
                      :capabilities capabilities}))

(defn usage-endpoint
  "Token and cost totals for one conversation, summed from stored message
  usage. This is what the UI's context bar reads."
  [{:keys [params]}]
  (if-let [conv (db/get-conversation (:conversation params))]
    (let [msgs (db/messages (:id conv) {})
          usages (keep :usage msgs)]
      (json-response 200 {:conversation_id (:id conv)
                          :usage (usage/summarize usages (or (:model conv) default-model))
                          :estimated-tokens (shelley.llm/estimate-tokens msgs)
                          :message-count (count msgs)}))
    (json-response 404 {:error "not found"})))

(def core-routes
  [["/version" {:get version}]
   ["/api/usage" {:get usage-endpoint}]
   ["/" {:get index}]
   ["/api/health" {:get health}]
   ["/api/conversations" {:get list-conversations :post create-conversation}]
   ["/api/conversations/:id" {:get get-conversation}]
   ["/api/conversations/:id/messages" {:post post-message}]
   ["/api/conversations/:id/cancel" {:post cancel}]
   ["/api/conversations/:id/events" {:get events}]
   ["/iframe/:id" {:get iframe-file}]
   ["/api/iframes/:id" {:get get-iframe}]])

(def routes
  "Everything routable: core API, workspace endpoints, and the other modules
  that bring their own tables."
  (into core-routes (concat files-api/routes git-api/routes term-api/routes)))

;; ---------------------------------------------------------------------------
;; Static UI assets

(defn- static-uri?
  "The UI lives under /ui/ so that index.html can be replaced wholesale without
  the server caring."
  [uri]
  (str/starts-with? uri "/ui/"))

(defn- serve-static [uri]
  (let [rel (subs uri 4)
        f (io/file "resources/ui" rel)]
    (if (and (.isFile f) (not (str/includes? rel "..")))
      {:status 200
       :headers {"content-type" (or ({"js" "application/javascript"
                                      "css" "text/css"
                                      "html" "text/html; charset=utf-8"
                                      "json" "application/json"
                                      "svg" "image/svg+xml"
                                      "woff2" "font/woff2"}
                                     (last (str/split (str rel) #"\.")))
                                  "application/octet-stream")}
       :body (slurp f)}
      {:status 404 :body "not found"})))

;; ---------------------------------------------------------------------------
;; Minimal router (path pattern -> handler)

(defn- segments [path]
  (->> (str/split (str/replace (first (str/split path #"\?")) #"^/" "") #"/")
       (remove str/blank?)
       vec))

(defn- match-one
  "Match one pattern against one path. Returns a params map or nil."
  [pattern-segs uri-segs]
  (when (= (count pattern-segs) (count uri-segs))
    (reduce (fn [params [p u]]
              (cond
                (nil? params) nil
                (str/starts-with? p ":") (assoc params (keyword (subs p 1)) u)
                (= p u) params
                :else nil))
            {}
            (map vector pattern-segs uri-segs))))

(defn- match-route [method uri]
  (let [segs (segments uri)
        verb (keyword (str/lower-case (name method)))]
    (some (fn [[pattern methods]]
            (when-let [h (get methods verb)]
              (when-some [params (match-one (segments pattern) segs)]
                [h params])))
          routes)))

(defn handler [req]
  (try
    (if (static-uri? (:uri req))
      (serve-static (:uri req))
      (if-let [[h params] (match-route (:request-method req) (:uri req))]
        (let [req (assoc req :params (merge (:params req) params))]
          (h req))
        {:status 404 :headers {"content-type" "application/json"} :body "{\"error\":\"not found\"}"}))
    (catch Exception e
      (binding [*out* *err*]
        (println "request failed:" (.getMessage e))
        (.printStackTrace e))
      (json-response 500 {:error (.getMessage e)}))))

(defn start!
  ([] (start! (Integer/parseInt (or (System/getenv "PORT") "8000"))))
  ([port]
   (db/init!)
   (http/run-server handler {:port port :legacy-return-value? false
                             :ip "0.0.0.0"
                             :thread 16
                             :queue-size 1024})))
