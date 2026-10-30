(ns shelley.tools.cdp
  "A small Chrome DevTools Protocol client.

  The original drives Chrome through chromedp. This is the same protocol with
  no library: launch a headless browser with `--remote-debugging-port=0`, read
  the port Chrome chose from `DevToolsActivePort` in its profile directory,
  open a WebSocket to the browser endpoint, then attach to a page target and
  address it by `sessionId` (flattened sessions). That flat form is what lets
  one socket drive a page, its network and its profiler without nested sockets.

  Everything here is synchronous: a tool call is one operation, and a
  callback-driven client would only move the blocking somewhere less visible.

  Messages are JSON objects carrying either an `id` (a response, matched to a
  promise) or a `method` (an event, handed to listeners and kept in a bounded
  ring so `wait-event!` can still find a recent one)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Locating and launching a browser

(defn chrome-candidates
  "Browser binaries to try, in order. The Playwright cache comes first because
  that is what `npx playwright install chromium` produces on a machine with no
  system Chrome — the situation this project is normally in."
  []
  (let [home (System/getProperty "user.home")
        playwright (io/file home ".cache" "ms-playwright")
        newest (fn [prefix tail]
                 (->> (or (.listFiles playwright) (make-array java.io.File 0))
                      (filter #(str/starts-with? (.getName %) prefix))
                      (sort-by #(.getName %) (fn [a b] (compare b a)))
                      (map #(io/file % tail))
                      (filter #(.canExecute ^java.io.File %))))]
    (concat
     (when (System/getenv "SHELLEY_CHROME_BIN")
       [(io/file (System/getenv "SHELLEY_CHROME_BIN"))])
     (when (System/getenv "CHROME_BIN")
       [(io/file (System/getenv "CHROME_BIN"))])
     (newest "chromium_headless_shell-" "chrome-linux/headless_shell")
     (newest "chromium-" "chrome-linux/chrome")
     (for [p ["/usr/bin/chromium" "/usr/bin/chromium-browser" "/usr/bin/google-chrome"
              "/usr/bin/google-chrome-stable" "/usr/bin/chrome-headless-shell"
              "/opt/google/chrome/chrome"
              "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"]]
       (io/file p)))))

(defn find-chrome
  "The first usable browser binary, or nil. Nil is a real answer: it means the
  browser tool degrades, not that the call should throw."
  []
  (when-let [^java.io.File f (->> (chrome-candidates)
                                  (filter #(and (.exists ^java.io.File %) (.canExecute ^java.io.File %)))
                                  first)]
    (.getPath f)))

(defn- read-devtools-port
  "Chrome writes the port it picked, then the browser websocket path, into
  DevToolsActivePort in the profile directory. Polling that file is more
  robust than parsing stderr, which differs between Chrome and
  headless_shell."
  [^java.io.File dir timeout-ms]
  (let [f (io/file dir "DevToolsActivePort")
        deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (cond
        (and (.exists f) (>= (.length f) 3))
        (let [[port path] (str/split-lines (slurp f))]
          (if (and port path)
            {:port (Long/parseLong (str/trim port)) :path (str/trim path)}
            (do (Thread/sleep 50) (recur))))

        (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "Chrome did not open a debugging port within " timeout-ms "ms")
                        {:kind :chrome-timeout}))

        :else (do (Thread/sleep 50) (recur))))))

(defn launch!
  "Start a headless Chrome. Returns {:proc :port :ws-url :user-data-dir :binary}.

  opts: :width :height :timeout-ms :args (extra command-line arguments)"
  [{:keys [width height timeout-ms args] :or {width 1280 height 800 timeout-ms 20000}}]
  (if-let [bin (find-chrome)]
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "clj-shelley-chrome-" (subs (str (java.util.UUID/randomUUID)) 0 8)))
          _ (.mkdirs dir)
          argv (into [bin]
                     (concat
                      ["--headless=new"
                       "--no-sandbox"
                       "--disable-gpu"
                       "--disable-dev-shm-usage"
                       "--no-first-run"
                       "--no-default-browser-check"
                       "--disable-background-timer-throttling"
                       "--disable-backgrounding-occluded-windows"
                       "--disable-renderer-backgrounding"
                       ;; WebAuthn crashes some headless builds; the original
                       ;; disables it for the same reason (issue #78 there).
                       "--disable-features=site-per-process,Translate,BlinkGenPropertyTrees,WebAuthentication"
                       "--remote-debugging-port=0"
                       (str "--user-data-dir=" (.getPath dir))
                       (str "--window-size=" width "," height)]
                      (or args [])))
          pb (doto (ProcessBuilder. (vec argv))
               (.redirectErrorStream true)
               (.redirectOutput (java.lang.ProcessBuilder$Redirect/to (io/file dir "chrome.log"))))
          proc (.start pb)]
      (try
        (let [{:keys [port path]} (read-devtools-port dir timeout-ms)]
          {:proc proc
           :port port
           :ws-url (str "ws://127.0.0.1:" port path)
           :user-data-dir (.getPath dir)
           :binary bin})
        (catch Throwable t
          (.destroyForcibly proc)
          (throw t))))
    (throw (ex-info (str "no Chrome or Chromium binary found. Install one with "
                         "`npx playwright install chromium`, or set SHELLEY_CHROME_BIN.")
                    {:kind :no-chrome}))))

(defn kill-browser!
  "Stop a browser started by launch!, descendants included. headless_shell
  forks zygote and renderer processes; killing only the parent leaves them."
  [launched]
  (when-let [^Process proc (:proc launched)]
    (try
      (doseq [h (iterator-seq (.iterator (.descendants (.toHandle proc))))]
        (.destroyForcibly ^java.lang.ProcessHandle h))
      (.destroyForcibly proc)
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Connection

(defn- dispatch! [state ^String text]
  (when-not (str/blank? text)
    (try
      (let [msg (json/parse-string text true)]
        (if (contains? msg :id)
          (when-let [p (get @(:pending state) (:id msg))]
            (swap! (:pending state) dissoc (:id msg))
            (deliver p msg))
          (do
            (swap! (:events state) (fn [v] (vec (take-last 1000 (conj v msg)))))
            (doseq [[_ f] (get @(:listeners state) (:method msg))]
              (try (f msg) (catch Exception e
                             (log/debug e "CDP listener failed")))))))
      (catch Exception _ nil))))

(defn- fail-pending! [state reason]
  (when-not @(:closed? state)
    (reset! (:closed? state) true)
    (doseq [[id p] @(:pending state)]
      (swap! (:pending state) dissoc id)
      (deliver p {:error {:message (str "CDP connection closed: " reason)}}))))

(defn connect!
  "Open a CDP connection to `ws-url`. Returns a state map for `send!`."
  [ws-url & {:keys [timeout-ms] :or {timeout-ms 20000}}]
  (let [state {:ws (atom nil)
               :open? (atom false)
               :closed? (atom false)
               :next-id (atom 0)
               :pending (atom {})
               :events (atom [])
               :listeners (atom {})
               :send-lock (Object.)}
        buffer (StringBuilder.)
        opened (promise)
        listener (reify WebSocket$Listener
                   (onOpen [_ ws]
                     ;; Demand is counted. Ask for a lot up front and for one
                     ;; more per message: after the last requested message
                     ;; arrives the JDK closes the channel, which then shows up
                     ;; as "Output closed" on the *next* send.
                     (.request ws Integer/MAX_VALUE)
                     (reset! (:open? state) true)
                     (deliver opened true))
                   (onText [_ ws data last]
                     (locking buffer
                       (.append buffer (str data))
                       (when last
                         (let [t (str buffer)]
                           (.setLength buffer 0)
                           (dispatch! state t))))
                     (.request ws 1))
                   (onClose [_ _ws _status reason]
                     (reset! (:open? state) false)
                     (fail-pending! state (str reason)))
                   (onError [_ _ws err]
                     (fail-pending! state (str (.getMessage ^Throwable err)))))
        ^HttpClient client (-> (HttpClient/newBuilder)
                   (.connectTimeout (java.time.Duration/ofMillis (long timeout-ms)))
                   (.build))
        ^WebSocket$Listener l listener
        builder (.newWebSocketBuilder client)
        ws (.join (.buildAsync ^java.net.http.WebSocket$Builder builder (URI/create ws-url) l))]
    ;; buildAsync completes at the handshake; onOpen runs after that on the
    ;; client's own thread. Sending before onOpen produces "Output closed",
    ;; so wait for it explicitly.
    (when (= ::timeout (deref opened (long timeout-ms) ::timeout))
      (throw (ex-info (str "CDP websocket did not open within " timeout-ms "ms")
                      {:kind :cdp-timeout})))

    (reset! (:ws state) ws)
    state))

(defn close! [state]
  (when-let [^WebSocket ws @(:ws state)]
    (try (.sendClose ws WebSocket/NORMAL_CLOSURE "done") (catch Exception _ nil))
    (try (.abort ws) (catch Exception _ nil)))
  (fail-pending! state "closed by client"))

(defn send!
  "Send one CDP command and return its result map.

  Throws ex-info {:kind :cdp-error} on a protocol error, {:kind :cdp-timeout}
  when no answer arrives. A protocol error is information the model should
  see, so the browser tool turns it into a tool-result rather than an
  exception at that layer."
  [state method params & {:keys [session-id timeout-ms] :or {timeout-ms 30000}}]
  (let [id (swap! (:next-id state) inc)
        p (promise)
        msg (cond-> {:id id :method method}
              params (assoc :params params)
              session-id (assoc :sessionId session-id))]
    (swap! (:pending state) assoc id p)
    (locking (:send-lock state)
      (.join (.sendText ^WebSocket @(:ws state) (json/generate-string msg) true)))
    (let [resp (deref p (long timeout-ms) ::timeout)]
      (swap! (:pending state) dissoc id)
      (cond
        (= ::timeout resp)
        (throw (ex-info (str "CDP command " method " timed out after " timeout-ms "ms")
                        {:kind :cdp-timeout :method method}))

        (:error resp)
        (throw (ex-info (str (get-in resp [:error :message] "CDP error") " (" method ")")
                        {:kind :cdp-error :method method
                         :code (get-in resp [:error :code])}))

        :else (:result resp)))))

(defn add-listener!
  "Call `f` for every event named `method`. Returns an id for remove-listener!."
  [state method f]
  (let [k (keyword (str (name method) "-" (subs (str (java.util.UUID/randomUUID)) 0 8)))]
    (swap! (:listeners state) assoc-in [method k] f)
    [method k]))

(defn remove-listener! [state [method k]]
  (swap! (:listeners state) update method dissoc k))

(defn expect-event!
  "A promise that will receive the next event named `method`. Register this
  *before* the command that triggers the event, or the event may be missed.
  Returns {:promise p :cancel (fn [])}."
  [state method]
  (let [p (promise)
        id (add-listener! state method (fn [msg] (when-not (realized? p) (deliver p msg))))]
    {:promise p :cancel #(remove-listener! state id)}))

(defn wait-event!
  "Wait for an event named `method` that has already arrived, or arrives within
  `timeout-ms`. Returns the event map or nil."
  [state method timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (if-let [ev (->> @(:events state) (filter #(= method (:method %))) last)]
        ev
        (if (> (System/currentTimeMillis) deadline)
          nil
          (do (Thread/sleep 25) (recur)))))))

;; ---------------------------------------------------------------------------
;; Page targets

(defn attach-page!
  "Create a fresh page target and attach to it. Returns {:target-id s
  :session-id s}: the session id is what every later Page/Runtime/Network
  command carries."
  [state]
  (let [{:keys [targetId]} (send! state "Target.createTarget" {:url "about:blank"})
        {:keys [sessionId]} (send! state "Target.attachToTarget"
                                  {:targetId targetId :flatten true})]
    {:target-id targetId :session-id sessionId}))
