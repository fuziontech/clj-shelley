(ns shelley.tools.browse
  "The `browser` tool: drive one headless Chrome through Chrome DevTools
  Protocol and hand the model what it can actually use — text for evaluation
  results, image blocks for screenshots, a summarised CPU profile rather than
  a 4MB `.cpuprofile`.

  One browser is shared per process and closed after idling, because launch
  costs about a second and most sessions alternate navigate/eval/screenshot
  within seconds. Actions are serialised on a lock: two parallel tool calls
  driving one page would interleave navigations and produce nonsense that
  looks like a browser bug.

  When no Chrome binary exists, `navigate` falls back to a plain HTTP fetch so
  the agent can still read a page. Every other action fails with a message
  that says exactly what is missing rather than pretending."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [shelley.tools.cdp :as cdp])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util Base64]))

(def screenshot-dir (str (System/getProperty "java.io.tmpdir") "/clj-shelley-screenshots"))
(def idle-close-ms (* 5 60 1000))
(def max-text 30000)
(def max-console-entries 500)
(def max-network-entries 500)

(defonce ^:private state (atom nil))
(defonce ^:private lock (Object.))
(defonce ^:private reaper (atom nil))

;; ---------------------------------------------------------------------------
;; Small helpers

(defn- truncate [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) "\n… [truncated: " (count s) " chars total]")
      s)))

(defn- text-result [s] {:content [{:type :text :text s}]})

(defn- error-result [s]
  {:is-error true :content [{:type :text :text s}]})

(defn parse-duration
  "Milliseconds from 1500, \"1.5s\", \"500ms\", \"2m\". Anything unreadable
  falls back to `default` instead of failing a tool call over a timeout."
  [v default]
  (cond
    (nil? v) default
    (number? v) (long v)
    (and (string? v) (re-matches #"\d+(?:\.\d+)?" v)) (long (Double/parseDouble (str v)))
    (string? v)
    (if-let [[_ n unit] (re-matches #"(\d+(?:\.\d+)?)\s*(ms|s|m)?" (str/trim v))]
      (long (* (Double/parseDouble n)
               (case (or unit "ms") "ms" 1 "s" 1000 "m" 60000 1)))
      default)
    :else default))

;; ---------------------------------------------------------------------------
;; Lifecycle

(defn close!
  "Close the shared browser, if any. Safe to call when none is running."
  []
  (locking lock
    (when-let [{:keys [conn launched]} @state]
      (reset! state nil)
      (try (cdp/close! conn) (catch Exception _ nil))
      (try (cdp/kill-browser! launched) (catch Exception _ nil)))))

(defn- start-reaper! []
  (when (nil? @reaper)
    (let [t (Thread. ^Runnable
                    (fn []
                      (loop []
                        (Thread/sleep 30000)
                        (try
                          (when-let [{:keys [idle-since]} @state]
                            (when (> (- (System/currentTimeMillis) @idle-since) idle-close-ms)
                              (close!)))
                          (catch Exception _ nil))
                        (recur))))]
      (.setDaemon t true)
      (.setName t "browser-reaper")
      (.start t)
      (reset! reaper t))))

(defn- console-entry [msg]
  (let [p (:params msg)
        args (get-in p [:args])
        render (fn [a] (or (:value a) (:description a) (:type a) ""))]
    {:type (or (:type p) "log")
     :text (if (seq args) (str/join " " (map render args)) (str (or (:value p) "")))
     :url (get-in msg [:params :url])
     :line (get-in msg [:params :lineNumber])}))

(defn touch!
  "Mark the shared browser as recently used, so the idle reaper leaves it up."
  []
  (when-let [b @state] (reset! (:idle-since b) (System/currentTimeMillis))))

(defn- on-console! [b msg]
  (let [m (:method msg)]
    (when (#{"Runtime.consoleAPICalled" "Log.entryAdded" "Runtime.exceptionThrown"} m)
      (swap! (:console b)
             (fn [v] (vec (take-last max-console-entries
                                    (conj v (assoc (console-entry msg) :method m)))))))))

(defn- on-network! [b msg]
  (let [p (:params msg)
        id (:requestId p)]
    (case (:method msg)
      "Network.requestWillBeSent"
      (swap! (:network b)
             (fn [v] (vec (take-last max-network-entries
                                    (conj v {:id id
                                             :url (:url (:request p))
                                             :method (:method (:request p))
                                             :type (:type p)
                                             :started (System/currentTimeMillis)})))))

      "Network.responseReceived"
      (swap! (:network b)
             (fn [v] (mapv (fn [e] (if (= id (:id e))
                                     (assoc e :status (get-in p [:response :status])
                                            :mime (get-in p [:response :mimeType]))
                                     e))
                           v)))

      "Network.loadingFailed"
      (swap! (:network b)
             (fn [v] (mapv (fn [e] (if (= id (:id e)) (assoc e :failed (:errorText p)) e)) v)))

      nil)))

(defn- ensure-browser!
  "Launched state, starting Chrome on first use. Throws {:kind :no-chrome}."
  []
  (or (when-let [b @state]
        (touch!)
        b)
      (locking lock
        (or @state
            (let [launched (cdp/launch! {:width 1280 :height 800})
                  conn (cdp/connect! (:ws-url launched))
                  page (cdp/attach-page! conn)
                  b {:launched launched
                     :conn conn
                     :session-id (:session-id page)
                     :target-id (:target-id page)
                     :console (atom [])
                     :network (atom [])
                     :network-on? (atom false)
                     :idle-since (atom (System/currentTimeMillis))
                     :profilers (atom {})
                     :trace-stream (atom nil)}]
              (cdp/add-listener! conn "Runtime.consoleAPICalled" #(on-console! b %))
              (cdp/add-listener! conn "Log.entryAdded" #(on-console! b %))
              (cdp/add-listener! conn "Runtime.exceptionThrown" #(on-console! b %))
              (cdp/add-listener! conn "Network.requestWillBeSent" #(on-network! b %))
              (cdp/add-listener! conn "Network.responseReceived" #(on-network! b %))
              (cdp/add-listener! conn "Network.loadingFailed" #(on-network! b %))
              (cdp/send! conn "Page.enable" {} {:session-id (:session-id page)})
              (cdp/send! conn "Runtime.enable" {} {:session-id (:session-id page)})
              (start-reaper!)
              (reset! state b)
              b)))))

(defn- send! [b method params]
  (cdp/send! (:conn b) method (or params {}) {:session-id (:session-id b) :timeout-ms 30000}))

;; ---------------------------------------------------------------------------
;; Page helpers

(defn- ready-state [b]
  (try (-> (send! b "Runtime.evaluate"
                  {:expression "document.readyState" :returnByValue true})
           :result :value)
       (catch Exception _ nil)))

(defn- wait-for-load! [b timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop [quiet 0]
      (let [ready (ready-state b)]
        (cond
          (= ready "complete") :complete
          (> (System/currentTimeMillis) deadline) :timeout
          :else (do (Thread/sleep 100) (recur (inc quiet))))))))

(defn- js-result->text [res]
  (let [r (:result res)]
    (cond
      (:exceptionDetails res)
      (str "JavaScript error: " (or (get-in res [:exceptionDetails :exception :description])
                                    (get-in res [:exceptionDetails :text])
                                    "unknown error"))

      (nil? r) "(no result)"
      (contains? r :value) (let [v (:value r)]
                             (if (string? v) v (json/generate-string v)))
      (:unserializableValue r) (:unserializableValue r)
      (:description r) (:description r)
      :else (pr-str r))))

(defn- http-fetch
  "Degraded navigate: no browser, but the agent still deserves the page."
  [url]
  (let [client (-> (HttpClient/newBuilder)
                   (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
                   (.connectTimeout (Duration/ofSeconds 20))
                   (.build))
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 30))
                (.header "user-agent" "clj-shelley/1.0")
                (.GET)
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:url url
     :status (.statusCode resp)
     :body (or (.body resp) "")}))

(defn- strip-tags [html]
  (-> html
      (str/replace #"(?is)<script.*?</script>" "")
      (str/replace #"(?is)<style.*?</style>" "")
      (str/replace #"(?is)<[^>]+>" " ")
      (str/replace #"&nbsp;" " ")
      (str/replace #"&amp;" "&")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"[ \t]+" " ")
      (str/replace #"\n\s*\n\s*" "\n")))

;; ---------------------------------------------------------------------------
;; Actions

(defn- action-navigate [b {:keys [url timeout] :as input}]
  (when (str/blank? url)
    (throw (ex-info "navigate requires :url" {:kind :bad-args})))
  (let [_ (send! b "Page.navigate" {:url url})
        state (wait-for-load! b (parse-duration timeout 20000))
        info (try (send! b "Runtime.evaluate"
                         {:expression "({url: location.href, title: document.title})"
                          :returnByValue true})
                  (catch Exception _ nil))
        v (:value (:result info))]
    (text-result (str "Navigated to " (or (:url v) url)
                      (when (:title v) (str "\nTitle: " (:title v)))
                      (case state :timeout "\n(load event did not finish before the timeout; the page may still be loading)" nil)))))

(defn- action-eval [b {:keys [expression await timeout]}]
  (when (str/blank? expression)
    (throw (ex-info "eval requires :expression" {:kind :bad-args})))
  (let [res (send! b "Runtime.evaluate"
                   {:expression expression
                    :returnByValue true
                    :awaitPromise (if (nil? await) true (boolean await))
                    :userGesture true
                    :timeout (parse-duration timeout 30000)})]
    (text-result (truncate (js-result->text res) max-text))))

(defn- action-resize [b {:keys [width height]}]
  (let [w (long (or width 1280))
        h (long (or height 800))]
    (send! b "Emulation.setDeviceMetricsOverride"
           {:width w :height h :deviceScaleFactor 1 :mobile false})
    (text-result (str "Viewport resized to " w "x" h))))

(defn- write-screenshot! [^String b64 ext]
  (.mkdirs (io/file screenshot-dir))
  (let [f (io/file screenshot-dir (str "shot-" (subs (str (java.util.UUID/randomUUID)) 0 8) "." ext))]
    (with-open [out (io/output-stream f)]
      (.write out (.decode (Base64/getDecoder) b64)))
    f))

(defn- selector-clip [b selector]
  (send! b "DOM.enable" {})
  (let [doc (send! b "DOM.getDocument" {:depth 1})
        node (send! b "DOM.querySelector" {:nodeId (get-in doc [:root :nodeId]) :selector selector})]
    (when (zero? (long (or (:nodeId node) 0)))
      (throw (ex-info (str "no element matches selector " (pr-str selector)) {:kind :not-found})))
    (let [model (send! b "DOM.getBoxModel" {:nodeId (:nodeId node)})
          [x1 y1 x2 y2 x3 y3 x4 y4] (-> model :model :content)]
      {:x (double (min x1 x2 x3 x4))
       :y (double (min y1 y2 y3 y4))
       :width (double (- (max x1 x2 x3 x4) (min x1 x2 x3 x4)))
       :height (double (- (max y1 y2 y3 y4) (min y1 y2 y3 y4)))})))

(defn- action-screenshot [b {:keys [selector format quality timeout]}]
  (let [params (cond-> {:format (or format "png")}
                 (= (or format "png") "jpeg") (assoc :quality (long (or quality 80)))
                 (not (str/blank? selector)) (assoc :clip (assoc (selector-clip b selector) :scale 1)))
        res (send! b "Page.captureScreenshot" params)
        f (write-screenshot! (:data res) (if (= (or format "png") "jpeg") "jpg" "png"))
        media (if (= (or format "png") "jpeg") "image/jpeg" "image/png")]
    {:content [{:type :text
                :text (str "Screenshot saved to " (.getPath f)
                           " (" (quot (.length f) 1024) "KB"
                           (when (not (str/blank? selector)) (str ", element " selector)) ").")}
               {:type :image :media-type media :data (:data res)}]}))

(defn- action-console-logs [b {:keys [limit]}]
  (let [n (long (or limit 50))
        entries (->> @(:console b) (take-last n))]
    (text-result (if (empty? entries)
                   "No console messages."
                   (str/join "\n" (map (fn [e]
                                          (str "[" (name (or (:type e) :log)) "] "
                                               (or (:text e) "")
                                               (when (:url e) (str "  (" (:url e) ")"))))
                                        entries))))))

(def devices
  {"iphone-13" {:width 390 :height 844 :scale 3 :mobile true}
   "iphone-15-pro" {:width 393 :height 852 :scale 3 :mobile true}
   "pixel-7" {:width 412 :height 915 :scale 2.625 :mobile true}
   "ipad-mini" {:width 744 :height 1133 :scale 2 :mobile true}
   "desktop" {:width 1920 :height 1080 :scale 1 :mobile false}
   "laptop" {:width 1440 :height 900 :scale 1 :mobile false}})

(defn- action-emulate-device [b {:keys [device]}]
  (if-let [d (get devices (some-> device str/lower-case))]
    (do (send! b "Emulation.setDeviceMetricsOverride"
               {:width (:width d) :height (:height d)
                :deviceScaleFactor (:scale d) :mobile (:mobile d)})
        (when (:mobile d)
          (send! b "Emulation.setTouchEmulationEnabled" {:enabled true}))
        (text-result (str "Emulating " device " (" (:width d) "x" (:height d) " @" (:scale d) "x).")))
    (error-result (str "Unknown device " (pr-str device) ". Known: " (str/join ", " (sort (keys devices)))))))

(defn- action-emulate-custom [b {:keys [width height device_scale_factor mobile touch]}]
  (let [w (long (or width 1280)) h (long (or height 800))
        scale (double (or device_scale_factor 1))]
    (send! b "Emulation.setDeviceMetricsOverride"
           {:width w :height h :deviceScaleFactor scale :mobile (boolean mobile)})
    (send! b "Emulation.setTouchEmulationEnabled" {:enabled (boolean (or touch mobile))})
    (text-result (str "Emulating " w "x" h " @" scale "x" (when mobile " (mobile)") "."))))

(defn- action-emulate-reset [b _]
  (send! b "Emulation.clearDeviceMetricsOverride" {})
  (send! b "Emulation.setEmulatedMedia" {:media "screen" :features []})
  (send! b "Emulation.setEmulatedVisionDeficiency" {:type "none"})
  (text-result "Cleared device and media emulation."))

(defn- action-dark-mode [b {:keys [enabled]}]
  (let [on (if (nil? enabled) true (boolean enabled))]
    (send! b "Emulation.setEmulatedMedia"
           {:features [{:name "prefers-color-scheme" :value (if on "dark" "light")}]})
    (text-result (str "prefers-color-scheme: " (if on "dark" "light")))))

(defn- action-media [b {:keys [media]}]
  (let [m (or media "screen")]
    (send! b "Emulation.setEmulatedMedia" {:media m})
    (text-result (str "Emulated media type: " m))))

(defn- action-network-enable [b _]
  (when-not @(:network-on? b)
    (send! b "Network.enable" {:maxTotalBufferSize (* 50 1024 1024)})
    (reset! (:network-on? b) true))
  (text-result "Network monitoring enabled."))

(defn- action-network-disable [b _]
  (when @(:network-on? b)
    (send! b "Network.disable" {})
    (reset! (:network-on? b) false))
  (text-result "Network monitoring disabled."))

(defn- action-network-log [b {:keys [limit] :as input}]
  ;; The input key is called `filter`; destructuring it under that name would
  ;; shadow clojure.core/filter for the whole body, so read it off the map.
  (let [n (long (or limit 50))
        url-fragment (:filter input)
        pat (when-not (str/blank? url-fragment) (str/lower-case url-fragment))
        rows (->> @(:network b)
                  (filter #(or (nil? pat)
                               (str/includes? (str/lower-case (str (:url %))) pat)))
                  (take-last n))]
    (text-result (if (empty? rows)
                   (if @(:network-on? b) "No requests captured yet."
                       "Network monitoring is off; call action=network_enable first.")
                   (str/join "\n"
                             (map (fn [r]
                                    (str (or (:status r) "---") " " (:method r) " " (:url r)
                                         (when (:type r) (str "  [" (:type r) "]"))
                                         (when (:failed r) (str "  FAILED: " (:failed r)))))
                                  rows))))))

(defn- action-network-cookies [b _]
  (let [res (send! b "Network.getAllCookies" {})
        cookies (:cookies res)]
    (text-result (if (empty? cookies)
                   "No cookies."
                   (str/join "\n" (map #(str (:name %) "=" (:value %) "  (" (:domain %) (:path %) ")")
                                        cookies))))))

(defn- action-network-clear-cache [b _]
  (send! b "Network.clearBrowserCache" {})
  (text-result "Browser cache cleared."))

(defn- ax-node->line [node ^long depth]
  (let [role (get-in node [:role :value])
        name (get-in node [:name :value])
        value (get-in node [:value :value])]
    (str (apply str (repeat depth "  "))
         (or role "?")
         (when (and name (not= name "")) (str " \"" (truncate name 80) "\""))
         (when (and value (not= value "")) (str " = " (truncate (str value) 80))))))

(defn- action-accessibility-tree [b {:keys [depth limit]}]
  (send! b "Accessibility.enable" {})
  (let [res (send! b "Accessibility.getFullAXTree" {})
        nodes (:nodes res)
        by-id (into {} (map (juxt :nodeId identity)) nodes)
        root (first nodes)
        max-depth (long (or depth 10))
        out (atom [])]
    (letfn [(walk [node d]
              (when (and node (<= d max-depth) (< (count @out) (long (or limit 400))))
                (let [ignored? (or (:ignored node)
                                   (#{"none" "generic" "InlineTextBox" "StaticText"}
                                    (get-in node [:role :value])))]
                  (when-not ignored? (swap! out conj (ax-node->line node d)))
                  (doseq [cid (or (:childIds node) [])]
                    (walk (get by-id cid) (if ignored? d (inc (long d))))))))]
      (walk root 0))
    (text-result (if (empty? @out)
                   "Accessibility tree is empty (call navigate first)."
                   (str/join "\n" @out)))))

(defn- action-accessibility-query [b {:keys [name role limit]}]
  (send! b "Accessibility.enable" {})
  (let [res (send! b "Accessibility.getFullAXTree" {})
        want-name (some-> name str/lower-case)
        want-role (some-> role str/lower-case)
        hits (->> (:nodes res)
                  (remove :ignored)
                  (filter #(and (or (nil? want-name)
                                    (str/includes? (str/lower-case (str (get-in % [:name :value])))
                                                   want-name))
                                (or (nil? want-role)
                                    (= want-role (str/lower-case (str (get-in % [:role :value])))))))
                  (take (long (or limit 50))))]
    (text-result (if (empty? hits)
                   (str "No accessibility node matches"
                        (when name (str " name~" (pr-str name)))
                        (when role (str " role=" (pr-str role))) ".")
                   (str/join "\n" (map-indexed (fn [i n] (str (inc i) ". " (ax-node->line n 0))) hits))))))

(defn- action-accessibility-node [b {:keys [selector]}]
  (when (str/blank? selector)
    (throw (ex-info "accessibility_node requires :selector" {:kind :bad-args})))
  (send! b "DOM.enable" {})
  (let [doc (send! b "DOM.getDocument" {:depth 1})
        node (send! b "DOM.querySelector" {:nodeId (get-in doc [:root :nodeId]) :selector selector})]
    (if (zero? (long (or (:nodeId node) 0)))
      (error-result (str "No element matches " (pr-str selector)))
      (let [ax (send! b "Accessibility.getPartialAXTree" {:nodeId (:nodeId node) :fetchRelatives true})
            nodes (:nodes ax)]
        (text-result (str/join "\n" (map #(ax-node->line % 0) nodes)))))))

(defn- action-profile-metrics [b _]
  (send! b "Performance.enable" {})
  (let [res (send! b "Performance.getMetrics" {})]
    (text-result (str/join "\n" (map (fn [m] (str (:name m) ": " (:value m))) (:metrics res))))))

(defn- profile-summary [profile]
  (let [nodes (:nodes profile)
        total (count (:samples profile))
        by-id (into {} (map (juxt :id identity) nodes))
        counts (frequencies (:samples profile))
        rows (->> counts
                  (sort-by val >)
                  (take 15)
                  (map (fn [[id n]]
                         (let [cf (get-in by-id [id :callFrame])]
                           {:pct (if (pos? total) (* 100.0 (/ (double n) total)) 0)
                            :func (or (:functionName cf) "(anonymous)")
                            :url (or (:url cf) "")
                            :line (inc (long (or (:lineNumber cf) 0)))}))))]
    (str "Total samples: " total "\n"
         "Top functions by self time:\n"
         (str/join "\n"
                   (map (fn [{:keys [pct func url line]}]
                          (str (format "%.1f%%" pct) "  " func "  "
                               (if (str/blank? url) "(native)" (str (last (str/split url #"/")) ":" line))))
                        rows)))))

(defn- action-profile-cpu-start [b _]
  (send! b "Profiler.enable" {})
  (send! b "Profiler.start" {})
  (swap! (:profilers b) assoc :cpu true)
  (text-result "CPU profiling started. Call action=profile_cpu_stop to collect."))

(defn- action-profile-cpu-stop [b _]
  (if-not (get @(:profilers b) :cpu)
    (error-result "CPU profiling was not started; call action=profile_cpu_start first.")
    (let [res (send! b "Profiler.stop" {})
          profile (:profile res)]
      (swap! (:profilers b) assoc :cpu false)
      (text-result (truncate (profile-summary profile) max-text)))))

(defn- action-profile-trace-start [b {:keys [categories]}]
  (send! b "Tracing.start"
         {:categories (if (str/blank? categories)
                        "devtools.timeline,disabled-by-default-devtools.timeline,v8.execute,blink.user_timing"
                        categories)
          :transferMode "ReturnAsStream"
          :options "sampling-frequency=10000"})
  (swap! (:profilers b) assoc :trace true)
  (text-result "Tracing started. Call action=profile_trace_stop to collect."))

(defn- read-stream-all
  "Drain a CDP IO stream handle into a string."
  [b handle]
  (let [buf (StringBuilder.)]
    (loop []
      (let [res (send! b "IO.read" {:handle handle})]
        (when-let [chunk (:data res)]
          (.append buf ^String chunk))
        (when (:base64Encoded res)
          (throw (ex-info "unexpected base64 trace stream" {:kind :trace})))
        (when-not (:eof res)
          (recur))))
    (str buf)))

(defn- action-profile-trace-stop [b _]
  (if-not (get @(:profilers b) :trace)
    (error-result "Tracing was not started; call action=profile_trace_start first.")
    (let [complete (cdp/expect-event! (:conn b) "Tracing.tracingComplete")
        _ (send! b "Tracing.end" {})
        ev (deref (:promise complete) 30000 nil)
        _ ((:cancel complete))]
    (swap! (:profilers b) assoc :trace false)
    (if-not ev
      (error-result "Tracing did not complete within 30s.")
      (let [handle (get-in ev [:params :stream])
            json-str (try (read-stream-all b handle)
                          (catch Exception e
                            (log/warn e "could not read trace stream")
                            nil))]
        (if (nil? json-str)
          (error-result "Tracing completed but the trace stream could not be read.")
          (let [f (io/file screenshot-dir (str "trace-" (subs (str (java.util.UUID/randomUUID)) 0 8) ".json"))]
            (.mkdirs (io/file screenshot-dir))
            (spit f json-str)
            (text-result (str "Trace saved to " (.getPath f) " (" (quot (.length f) 1024) "KB). "
                              "Load it in Chrome DevTools Performance panel or https://ui.perfetto.dev.")))))))))

(defn- action-profile-coverage-start [b _]
  (send! b "Profiler.enable" {})
  (send! b "Profiler.startPreciseCoverage" {:callCount true :detailed false})
  (swap! (:profilers b) assoc :coverage true)
  (text-result "Precise coverage started."))

(defn- action-profile-coverage-stop [b _]
  (if-not (get @(:profilers b) :coverage)
    (error-result "Coverage was not started; call action=profile_coverage_start first.")
    (let [res (send! b "Profiler.takePreciseCoverage" {})
        _ (send! b "Profiler.stopPreciseCoverage" {})
        _ (swap! (:profilers b) assoc :coverage false)
        rows (->> (:result res)
                  (keep (fn [script]
                          (let [fns (:functions script)
                                total (reduce + 0 (map #(count (or (:ranges %) [])) fns))
                                covered (count (filter (fn [fn-map]
                                                         (some #(pos? (long (or (:count %) 0)))
                                                               (:ranges fn-map)))
                                                       fns))]
                            (when (pos? (count fns))
                              {:url (str (:url script))
                               :total (count fns)
                               :covered covered}))))
                  (sort-by :total >)
                  (take 30))]
    (text-result (if (empty? rows)
                   "No coverage data (nothing executed)."
                   (str "JS function coverage (functions executed / total):\n"
                        (str/join "\n"
                                  (map (fn [{:keys [url total covered]}]
                                         (str (format "%3d%%" (if (pos? total) (quot (* 100 covered) total) 0))
                                              "  " covered "/" total "  "
                                              (if (str/blank? url) "(inline)" (truncate url 120))))
                                       rows))))))))

(defn- help-result [topic body] (text-result (str topic "\n\n" body)))

(def ^:private helps
  {:emulate_help (str "emulate_help — device and media emulation\n\n"
                      "- emulate_device {:device}      known: " (str/join ", " (sort (keys devices)))
                      "\n- emulate_custom {:width :height :device_scale_factor :mobile :touch}"
                      "\n- emulate_reset                   clear overrides"
                      "\n- emulate_dark_mode {:enabled}   prefers-color-scheme"
                      "\n- emulate_media {:media}         \"screen\" or \"print\"")
   :network_help (str "network_help — HTTP traffic\n\n"
                      "- network_enable                 start recording"
                      "\n- network_get_log {:limit :filter} captured requests"
                      "\n- network_cookies                cookies in the browser"
                      "\n- network_clear_cache            drop the browser cache"
                      "\n- network_clear                  drop captured entries"
                      "\n- network_disable                stop recording")
   :accessibility_help (str "accessibility_help — the accessibility tree\n\n"
                            "- accessibility_tree {:depth :limit}  full tree as indented roles and names"
                            "\n- accessibility_query {:name :role :limit} find nodes"
                            "\n- accessibility_node {:selector}    the AX node for a CSS selector")
   :profile_help (str "profile_help — performance\n\n"
                      "- profile_metrics               Performance.getMetrics counters"
                      "\n- profile_cpu_start / profile_cpu_stop   CPU profile, summarised"
                      "\n- profile_trace_start / profile_trace_stop   DevTools trace to a .json file"
                      "\n- profile_coverage_start / profile_coverage_stop   JS function coverage")})

(defn- screenshot-screencast-unsupported [action]
  (error-result (str action " is not implemented in the Clojure port: it needs an MP4 muxer "
                     "for the CDP screencast frames. Use repeated action=screenshot instead, "
                     "or drive the page with eval.")))

(defn- dispatch [b {:keys [action] :as input}]
  (case (some-> action str
                (str/replace #"([a-z0-9])([A-Z])" "$1_$2")
                str/lower-case
                (str/replace "-" "_"))
    "navigate" (action-navigate b input)
    "eval" (action-eval b input)
    "resize" (action-resize b input)
    "screenshot" (action-screenshot b input)
    "console_logs" (action-console-logs b input)
    "clear_console_logs" (do (reset! (:console b) []) (text-result "Console log cleared."))
    "screencast_start" (screenshot-screencast-unsupported "screencast_start")
    "screencast_stop" (screenshot-screencast-unsupported "screencast_stop")
    "screencast_status" (screenshot-screencast-unsupported "screencast_status")
    "emulate_help" (help-result "emulate_help" (get helps :emulate_help))
    "emulate_device" (action-emulate-device b input)
    "emulate_custom" (action-emulate-custom b input)
    "emulate_reset" (action-emulate-reset b input)
    "emulate_dark_mode" (action-dark-mode b input)
    "emulate_media" (action-media b input)
    "network_help" (help-result "network_help" (get helps :network_help))
    "network_enable" (action-network-enable b input)
    "network_disable" (action-network-disable b input)
    "network_get_log" (action-network-log b input)
    "network_clear" (do (reset! (:network b) []) (text-result "Network log cleared."))
    "network_cookies" (action-network-cookies b input)
    "network_clear_cache" (action-network-clear-cache b input)
    "accessibility_help" (help-result "accessibility_help" (get helps :accessibility_help))
    "accessibility_tree" (action-accessibility-tree b input)
    "accessibility_query" (action-accessibility-query b input)
    "accessibility_node" (action-accessibility-node b input)
    "profile_help" (help-result "profile_help" (get helps :profile_help))
    "profile_metrics" (action-profile-metrics b input)
    "profile_cpu_start" (action-profile-cpu-start b input)
    "profile_cpu_stop" (action-profile-cpu-stop b input)
    "profile_trace_start" (action-profile-trace-start b input)
    "profile_trace_stop" (action-profile-trace-stop b input)
    "profile_coverage_start" (action-profile-coverage-start b input)
    "profile_coverage_stop" (action-profile-coverage-stop b input)
    nil (error-result "browser requires :action")
    (error-result (str "unknown browser action: " action))))

(defn run-browser
  "Run one browser action. `input` is the keywordised tool input."
  [input]
  (let [action (:action input)
        degraded? (nil? (cdp/find-chrome))]
    (cond
      (str/blank? action) (error-result "browser requires :action")

      (and degraded? (not= "navigate" (some-> action str/lower-case)))
      (error-result (str "No Chrome/Chromium binary is installed, so only action=navigate "
                         "works (it falls back to an HTTP fetch). Install one with "
                         "`npx playwright install chromium` or set SHELLEY_CHROME_BIN."))

      degraded?
      (if (str/blank? (:url input))
        (error-result "navigate requires :url")
        (try
          (let [{:keys [url status body]} (http-fetch (:url input))]
            (text-result (str "Fetched " url " (HTTP " status ") without a browser — "
                              "JavaScript did not run. Text content:\n\n"
                              (truncate (strip-tags body) 8000))))
          (catch Exception e
            (error-result (str "HTTP fetch of " (:url input) " failed: " (.getMessage e))))))

      :else
      (try
        (let [b (ensure-browser!)]
          (locking lock
            (try
              (let [res (dispatch b input)]
                (touch!)
                res)
              (catch clojure.lang.ExceptionInfo e
                (error-result (or (ex-message e) "browser action failed"))
                ;; A dead connection poisons every later call; drop it.
                (when (#{:cdp-error :cdp-timeout} (:kind (ex-data e))) (close!))))) )
        (catch clojure.lang.ExceptionInfo e
          (if (= :no-chrome (:kind (ex-data e)))
            (error-result (ex-message e))
            (error-result (str "browser failed: " (ex-message e)))))
        (catch Exception e
          (error-result (str "browser failed: " (.getMessage e))))))))

(defn browser-tool-def []
  {:name "browser"
   :description
   (str "Control a headless Chrome over the DevTools protocol. One page, kept open between calls.\n\n"
        "Navigate: navigate :url — loads the page and waits for the load event.\n"
        "Script: eval :expression — runs JavaScript in the page, returns the value. "
        "The whole point of a browser: read rendered text, click things (document.querySelector('x').click()), "
        "fill inputs, or scrape data after the page's own JS ran.\n"
        "Screenshots: screenshot [:selector :format :quality] — returns an image you can see.\n"
        "Console: console_logs [:limit], clear_console_logs — what the page logged.\n"
        "Viewport: resize :width :height.\n"
        "Emulation: emulate_help, emulate_device :device, emulate_custom, emulate_reset, emulate_dark_mode, emulate_media.\n"
        "Network: network_help, network_enable, network_get_log [:limit :filter], network_cookies, network_clear_cache, network_disable.\n"
        "Accessibility: accessibility_help, accessibility_tree, accessibility_query [:name :role], accessibility_node :selector — "
        "the way to find things by their accessible name instead of guessing CSS.\n"
        "Profiling: profile_help, profile_metrics, profile_cpu_start/stop, profile_trace_start/stop, profile_coverage_start/stop.\n\n"
        "Start with navigate, then eval or screenshot.")
   :input-schema
   {:type "object"
    :properties
    {:action {:type "string"
              :enum ["navigate" "eval" "resize" "screenshot" "console_logs" "clear_console_logs"
                     "screencast_start" "screencast_stop" "screencast_status"
                     "emulate_help" "emulate_device" "emulate_custom" "emulate_reset"
                     "emulate_dark_mode" "emulate_media"
                     "network_help" "network_enable" "network_disable" "network_get_log"
                     "network_clear" "network_cookies" "network_clear_cache"
                     "accessibility_help" "accessibility_tree" "accessibility_query" "accessibility_node"
                     "profile_help" "profile_metrics" "profile_cpu_start" "profile_cpu_stop"
                     "profile_trace_start" "profile_trace_stop" "profile_coverage_start" "profile_coverage_stop"]
              :description "The operation to perform."}
     :url {:type "string" :description "URL to navigate to (navigate)."}
     :expression {:type "string" :description "JavaScript to evaluate (eval)."}
     :await {:type "boolean" :description "Await promises returned by the expression (default true)."}
     :width {:type "integer" :description "Viewport width (resize, emulate_custom)."}
     :height {:type "integer" :description "Viewport height (resize, emulate_custom)."}
     :limit {:type "integer" :description "Maximum entries to return."}
     :selector {:type "string" :description "CSS selector (screenshot, accessibility_node)."}
     :timeout {:type "string" :description "Timeout such as \"15s\" or \"500ms\"."}
     :format {:type "string" :enum ["png" "jpeg"] :description "Screenshot format."}
     :quality {:type "integer" :description "JPEG quality 0-100."}
     :max_width {:type "integer" :description "Screencast frame width."}
     :max_height {:type "integer" :description "Screencast frame height."}
     :every_nth_frame {:type "integer" :description "Screencast frame sampling."}
     :device {:type "string" :description "Device preset name (emulate_device)."}
     :device_scale_factor {:type "number" :description "Device pixel ratio (emulate_custom)."}
     :mobile {:type "boolean" :description "Emulate a mobile viewport (emulate_custom)."}
     :touch {:type "boolean" :description "Enable touch emulation (emulate_custom)."}
     :enabled {:type "boolean" :description "Enable or disable (emulate_dark_mode)."}
     :media {:type "string" :description "Media type, e.g. screen or print (emulate_media)."}
     :filter {:type "string" :description "Substring filter for URLs (network_get_log)."}
     :depth {:type "integer" :description "Maximum tree depth (accessibility_tree)."}
     :name {:type "string" :description "Accessible name substring (accessibility_query)."}
     :role {:type "string" :description "Accessibility role, e.g. button (accessibility_query)."}
     :categories {:type "string" :description "Trace categories (profile_trace_start)."}}
    :required ["action"]}
   :handler (fn [ctx args] (run-browser args))})
