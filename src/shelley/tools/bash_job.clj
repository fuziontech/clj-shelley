(ns shelley.tools.bash-job
  "Background bash jobs.

  A shell command that outlives its tool call becomes a *job*: a bash process
  in its own session that appends to a log file and records its own exit
  status. Keeping the exit status in a file rather than in JVM memory is what
  lets a job survive the process that started it — the log is still there
  after a restart, and `kill -- -PGID` still works from a terminal.

  The wrapper traps HUP/INT/TERM so that killing the job's process group stops
  the command but not the wrapper; the wrapper then writes the exit file. That
  file is the only source of truth for how a job ended.

  Lifecycle, and what the loop has to do about it:

    start!      -> job map, log file, wrapper process, exit promise
    wait!       -> blocks up to N ms for the exit promise (foreground case)
    background! -> marks the job as reported-to-the-agent, so that when it
                   exits its `emit` receives {:type :background-job-completion}
                   and the conversation gets that text as a new user message

  The completion event is the contract with the rest of the system:

    {:type :background-job-completion
     :job-id ab12cd34 :conversation-id ... :tool-use-id ...
     :exit-code 0 (or nil for a lost job) :duration 1000ms
     :log-path ... :tail ... :text ...}
    ;; :text is the human-readable notice.

  `completion-message` turns such an event into the message the loop should
  inject. The event is emitted regardless, so a UI can show it even when no
  injection path exists."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [shelley.sandbox :as sandbox])
  (:import [java.io File RandomAccessFile]
           [java.util.concurrent TimeUnit]))

(def default-background-after-ms 60000)
(def progress-max-bytes (* 10 1024))
(def notice-tail-lines 20)
(def max-line-length 200)

;; ---------------------------------------------------------------------------
;; Paths and ids

(defn job-dir
  "Where job logs and exit files live. Same idea as the original's
  $TMPDIR/shelley-jobs."
  []
  (let [d (io/file (System/getProperty "java.io.tmpdir") "clj-shelley-jobs")]
    (.mkdirs d)
    d))

(defn new-job-id []
  (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 8))

;; ---------------------------------------------------------------------------
;; Small text helpers, shared with bash output formatting

(defn truncate-line [line]
  (if (> (count line) max-line-length)
    (str (subs line 0 max-line-length) "...")
    line))

(defn first-line [s]
  (let [s (str s)
        i (.indexOf s "\n")]
    (if (neg? i) s (str (subs s 0 i) " ..."))))

(defn humanize-bytes [n]
  (cond
    (< n (* 4 1024)) (str n "B")
    (< n (* 1024 1024)) (str (Math/round (double (/ n 1024.0))) "kB")
    (< n (* 1024 1024 1024)) (str (Math/round (double (/ n (* 1024.0 1024.0)))) "MB")
    :else "more than 1GB"))

(defn humanize-duration [ms]
  (let [ms (long (or ms 0))]
    (cond
      (< ms 1000) (str ms "ms")
      (< ms 60000) (str (/ (Math/round (/ ms 100.0)) 10.0) "s")
      :else (let [m (quot ms 60000)
                  s (quot (mod ms 60000) 1000)]
              (str m "m" s "s")))))

(defn read-tail
  "Up to `max-bytes` from the end of `path`, dropping the possibly-partial
  first line when the read was truncated. Missing or unreadable files give a
  short explanation rather than an exception: this is called from a progress
  timer, where throwing would be pointless noise."
  [path max-bytes]
  (let [f (io/file path)]
    (if-not (.exists f)
      ""
      (try
        (with-open [raf (RandomAccessFile. f "r")]
          (let [size (.length raf)
                start (max 0 (- size max-bytes))
                truncated? (pos? start)
                buf (byte-array (- size start))]
            (.seek raf start)
            (.readFully raf buf)
            (let [s (String. buf "UTF-8")]
              (if truncated?
                (let [i (.indexOf s "\n")]
                  (if (and (>= i 0) (< i (dec (count s)))) (subs s (inc i)) s))
                s))))
        (catch Exception e
          (str "(could not read job output: " (.getMessage e) ")"))))))

(defn log-tail
  "At most the last n lines of the log at path, each truncated to
  max-line-length, with a trailing newline when non-empty."
  [path n]
  (let [tail (str/replace (read-tail path (long (* n (inc max-line-length)))) #"\n+$" "")]
    (if (str/blank? tail)
      ""
      (->> (str/split tail #"\n")
           (take-last n)
           (map truncate-line)
           (str/join "\n")
           (str "\n")))))

;; ---------------------------------------------------------------------------
;; Process control

(def ^:private wrapper
  "Runs $1, then records its exit status in $2 atomically. The trap is why the
  wrapper survives `kill -- -PGID`: it ignores the terminating signal, and the
  command it runs (a separate process) does not."
  (str "trap : HUP INT TERM\n"
       "bash --login -c \"$1\"\n"
       "s=$?\n"
       "printf '%d\\n' \"$s\" >\"$2.tmp\" && mv -f -- \"$2.tmp\" \"$2\"\n"
       "exit \"$s\"\n"))

(defn- setsid-available? []
  (try
    (zero? (.exitValue (.waitFor (ProcessBuilder. ["setsid" "--version"])
                                2 TimeUnit/SECONDS)))
    (catch Exception _ false)))

(def ^:private setsid? (delay (setsid-available?)))

(defonce ^:private jobs (atom {}))

(defn- write-exit-file [path code]
  (let [tmp (str path ".tmp")]
    (spit tmp (str code "\n"))
    (.renameTo (io/file tmp) (io/file path))))

(defn exit-code
  "The job's recorded exit status, or nil when it never recorded one (the
  host rebooted, or the wrapper was SIGKILLed and the JVM that started it is
  gone)."
  [job]
  (try
    (let [s (str/trim (slurp (:exit-path job)))]
      (when (re-matches #"-?\d+" s) (Long/parseLong s)))
    (catch Exception _ nil)))

(defn- watcher
  "Wait for the wrapper, guarantee the exit file exists, and resolve the exit
  promise. Runs on its own thread; must never throw."
  [job]
  (let [{:keys [^Process proc exit-path exited]} job]
    (try
      (let [code (.waitFor proc)]
        (when-not (.exists (io/file exit-path))
          (write-exit-file exit-path code))
        (deliver exited {:exit-code (or (exit-code job) code)
                         :ended-at (System/currentTimeMillis)}))
      (catch Throwable t
        (log/warn t "background job watcher failed")
        (deliver exited {:exit-code nil :ended-at (System/currentTimeMillis)})))))

(defn start!
  "Start `command` under bash and return a job map. The command runs in its
  own session (via setsid) so `kill -- -PGID` reaches only it.

  opts: :command :working-dir :conversation-id :tool-use-id :env (extra
  environment variables, e.g. SHELLEY_*)."
  [{:keys [command working-dir conversation-id tool-use-id env]}]
  (let [dir (job-dir)
        id (new-job-id)
        log-file (io/file dir (str id ".log"))
        exit-path (.getPath (io/file dir (str id ".exit")))
        prefix (if @setsid? ["setsid"] [])
        inner (into (vec prefix)
                    ["bash" "-c" wrapper "shelley-job" command exit-path])
        ;; The sandbox gets a private /tmp, so the job directory must be bound
        ;; through or the wrapper's exit status lands somewhere we cannot read
        ;; and every job looks lost. wrap-argv returns nil when sandboxing is
        ;; off, which keeps this one code path.
        argv (or (sandbox/wrap-argv working-dir inner [[(.getPath dir) (.getPath dir)]])
                 inner)
        pb (doto (ProcessBuilder. argv)
             (.directory (io/file working-dir))
             (.redirectErrorStream true)
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/to log-file)))
        e (.environment pb)]
    ;; Strip inherited SHELLEY_* so we control exactly what the command sees.
    (doseq [k (keys e)] (when (str/starts-with? k "SHELLEY_") (.remove e k)))
    (.put e "PAGER" "cat")
    (.put e "GIT_PAGER" "cat")
    (.put e "DEBIAN_FRONTEND" "noninteractive")
    (.put e "EDITOR" "/bin/false") ; interactive editors cannot work here
    (.put e "GIT_SEQUENCE_EDITOR" "echo 'To do an interactive rebase, run it in a tmux session.' && exit 1")
    (doseq [[k v] env] (when v (.put e (name k) (str v))))
    (let [proc (.start pb)
          job {:id id
               :command command
               :conversation-id conversation-id
               :tool-use-id tool-use-id
               :working-dir working-dir
               :log-path (.getPath log-file)
               :exit-path exit-path
               ;; With setsid the child is a session leader, so its pid is
               ;; also its process group id.
               :pgid (.pid proc)
               :started-at (System/currentTimeMillis)
               :proc proc
               :exited (promise)
               :background? false}]
      (swap! jobs assoc id job)
      (doto (Thread. ^Runnable (fn [] (watcher job)))
        (.setDaemon true)
        (.setName (str "bash-job-" id))
        (.start))
      job)))

(defn running? [job]
  (not (realized? (:exited job))))

(defn wait!
  "Block up to `timeout-ms` for the job to exit. Returns {:finished? bool
  :exit-code long-or-nil}."
  [job timeout-ms]
  (let [res (deref (:exited job) timeout-ms ::timeout)]
    (if (= ::timeout res)
      {:finished? false :exit-code nil}
      {:finished? true :exit-code (:exit-code res)})))

(defn kill!
  "SIGTERM the job's process group, leaving the wrapper alive to record the
  exit status. Returns :killed, :gone, or throws on an unexpected failure."
  [job]
  (if-not (running? job)
    :gone
    (try
      (if @setsid?
        (let [p (.start (ProcessBuilder. ["kill" "-TERM" (str "--")
                                          (str "-" (:pgid job))]))]
          (when-not (zero? (.waitFor p 5 TimeUnit/SECONDS))
            (throw (ex-info "kill failed" {:job (:id job)})))
          :killed)
        ;; Without setsid the child shares our group, so walking the process
        ;; tree is the only safe way to stop it.
        (do (doseq [h (iterator-seq (.iterator (.descendants (.toHandle (:proc job)))))]
              (.destroyForcibly ^java.lang.ProcessHandle h))
            (.destroy ^java.lang.ProcessHandle (.toHandle (:proc job)))
            :killed))
      (catch Exception e
        (log/warn e "could not kill background job" (:id job))
        :gone))))

;; ---------------------------------------------------------------------------
;; Reporting to the conversation

(defn outcome
  "How the job ended: exit code, run time and log tail. Exit code is nil for a
  job that left no exit status."
  [job]
  (let [code (exit-code job)
        ended (or (:ended-at (when (realized? (:exited job)) (deref (:exited job) 0 nil)))
                  (System/currentTimeMillis))]
    {:job job
     :exit-code code
     :elapsed-ms (when code (- ended (:started-at job)))
     :tail (str/replace (log-tail (:log-path job) notice-tail-lines) #"\n+$" "")}))

(defn- outcome-notice [{:keys [job exit-code elapsed-ms tail]}]
  (let [{:keys [id command log-path]} job]
    (str (if (some? exit-code)
           (str "Background job " id " finished: exit " exit-code ", "
                (humanize-duration elapsed-ms) ". Log: " log-path)
           (str "Background job " id " lost (host rebooted or killed). Log: " log-path))
         "\nCommand: " (first-line command)
         (when-not (str/blank? tail) (str "\n\n" tail)))))

(defn completion-message
  "The user message the loop should inject for a :background-job-completion
  event. Kept here so the loop and the server agree on its shape."
  [event]
  {:role :user
   :content [{:type :text :text (:text event)}]
   :user-data {:background-job-id (:job-id event)
               :command (:command event)
               :exit-code (:exit-code event)
               :log-path (:log-path event)
               :tail (:tail event)
               :duration (:duration event)}})

(defn background!
  "Hand a still-running job back to the agent: the tool result says where the
  output will arrive, and when the job exits `emit` fires
  :background-job-completion (plus :inject-message if ctx supplied one).

  Returns the text the model sees."
  [job {:keys [emit inject-message]} how]
  (swap! jobs update (:id job) assoc :background? true)
  (let [tail (str/replace (log-tail (:log-path job) notice-tail-lines) #"\n+$" "")
        text (str (when-not (str/blank? tail) (str tail "\n"))
                  "[" how " background job " (:id job) " (PGID " (:pgid job) "). Log: "
                  (:log-path job) "\n"
                  "Its output will arrive as a new message when it exits; never poll or sleep for it. "
                  "Continue only with work that doesn't need its output; otherwise end your turn. "
                  "Cancel with `kill -- -" (:pgid job) "`.]")]
    (future
      (let [_ (deref (:exited job))
            o (outcome job)
            event (merge {:type :background-job-completion
                          :job-id (:id job)
                          :conversation-id (:conversation-id job)
                          :tool-use-id (:tool-use-id job)
                          :command (:command job)
                          :log-path (:log-path job)
                          :exit-code (:exit-code o)
                          :duration (humanize-duration (:elapsed-ms o))
                          :tail (:tail o)
                          :text (outcome-notice o)}
                         {})]
        (try
          (when inject-message (inject-message (completion-message event)))
          (when emit (emit event))
          (catch Throwable t (log/warn t "failed to report background job exit")))
        (swap! jobs update (:id job) assoc :reported? true)))
    text))

(defn list-jobs
  "Running (and unreported) jobs, optionally for one conversation, oldest
  first. This is what the UI drawer and any job tool read."
  ([] (list-jobs nil))
  ([conversation-id]
   (->> (vals @jobs)
        (filter (fn [j] (and (or (nil? conversation-id)
                                 (= conversation-id (:conversation-id j)))
                             (or (running? j) (not (:reported? j))))))
        (sort-by :started-at)
        (mapv (fn [j] {:job-id (:id j)
                       :command (:command j)
                       :pgid (:pgid j)
                       :log-path (:log-path j)
                       :started-at (:started-at j)
                       :running (running? j)
                       :tail (str/replace (log-tail (:log-path j) notice-tail-lines) #"\n+$" "")}))
        vec)))

(defn find-job
  "The full job map for an id, or nil. The list for a UI is summaries; killing
  and reporting need the process and the exit promise."
  [job-id]
  (get @jobs (str job-id)))

(defn forget!
  "Drop a job from the registry. Called once its log has been consumed, so the
  atom does not grow without bound over a long-lived server."
  [job]
  (swap! jobs dissoc (:id job)))

(defn progress-loop
  "Report the log tail every 500ms while the job runs, like the original's
  logProgressLoop. Returns a stop function; calling it emits a final tail."
  [job emit]
  (let [stopped (atom false)
        last-seen (atom "")
        emit-tail (fn []
                    (let [t (read-tail (:log-path job) progress-max-bytes)]
                      (when (and emit (not= t @last-seen))
                        (reset! last-seen t)
                        (emit {:type :tool-progress
                               :tool-use-id (:tool-use-id job)
                               :text t}))))
        t (doto (Thread.
                 ^Runnable
                 (fn []
                   (while (not @stopped)
                     (emit-tail)
                     (Thread/sleep 500))
                   (emit-tail)))
            (.setDaemon true)
            (.setName (str "bash-job-progress-" (:id job)))
            (.start))]
    (fn []
      (reset! stopped true)
      (try (.join ^Thread t 2000) (catch InterruptedException _)))))
