(ns shelley.terminal
  "Interactive terminal sessions for the UI's exec pane.

  A terminal needs a pty, not a pipe: programs change behaviour when they are
  talking to one (line buffering, colour, job control). The JVM cannot allocate
  a pty portably, so we use `script`, which exists precisely to run a program
  under one.

  Sessions are backed by **tmux** when it is installed, and that is the point:
  the shell outlives the server. Close the browser, restart the process, come
  back tomorrow — the session is still there with its scrollback. Without tmux
  we fall back to a plain pty that dies with the process.

  Frames are JSON over the websocket:
  {:type :input|:resize|:output|:exit|:history|:error}."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- which [cmd]
  (some (fn [dir]
          (let [f (io/file dir cmd)]
            (when (.canExecute f) (.getPath f))))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn- script-path [] (which "script"))
(defn- tmux-path [] (which "tmux"))

(defn available? [] (some? (script-path)))
(defn durable? []
  (and (available?) (some? (tmux-path))))

(defonce sessions (atom {}))

(def ^:private tmux-socket "clj-shelley")

(defn- tmux
  "Run a tmux command against our own socket, so we never collide with the
  sessions a user has open in their own tmux."
  [& args]
  (let [pb (doto (ProcessBuilder. (into [(tmux-path) "-L" tmux-socket] args))
             (.redirectErrorStream true))
        p (.start pb)
        out (slurp (.getInputStream p))]
    (.waitFor p)
    {:exit (.exitValue p) :out (str/trim out)}))

(defn session-name
  "Stable tmux session name for a conversation."
  [id]
  (str "shelley-" (str/replace (str id) #"[^A-Za-z0-9_-]" "") ))

(defn durable-exists? [name]
  (and (durable?) (zero? (:exit (tmux "has-session" "-t" name)))))

(defn list-durable []
  (if-not (durable?)
    []
    (let [{:keys [exit out]} (tmux "list-sessions" "-F" "#{session_name}")]
      (if (zero? exit) (remove str/blank? (str/split-lines out)) []))))

(defn ensure-durable!
  "Create the tmux session if it is not running. Idempotent."
  [name cwd]
  (when (and (durable?) (not (durable-exists? name)))
    (let [{:keys [exit out]} (tmux "new-session" "-d" "-s" name
                                   "-c" (str (or cwd "."))
                                   "-x" "120" "-y" "32")]
      (when-not (zero? exit)
        (throw (ex-info (str "could not create terminal session: " out) {}))))
    (tmux "set-option" "-t" name "status" "off")
    (tmux "set-option" "-t" name "history-limit" "10000"))
  name)

(defn kill-durable! [name]
  (when (durable?)
    (tmux "kill-session" "-t" name)))

(defn scrollback
  "The last `lines` lines of the session, formatted output only. This is what
  makes reopening a terminal show what you were looking at rather than a fresh
  prompt."
  ([name] (scrollback name 2000))
  ([name lines]
   (when (durable?)
     (let [{:keys [exit out]} (tmux "capture-pane" "-p" "-J" "-t" name
                                    "-S" (str "-" lines))]
       (when (zero? exit) out)))))

;; ---------------------------------------------------------------------------
;; Pty process handling

(defn- reader-loop [session]
  (let [in (.getInputStream ^Process (:proc session))
        buf (byte-array 8192)]
    (try
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (let [s (String. buf 0 n "UTF-8")]
              (swap! (:output session) conj s)
              ;; Read through the atom: a client that reattaches replaces the
              ;; callback, and output must follow the new client rather than
              ;; the one that opened the session.
              (when-let [f @(:on-output session)]
                (try (f s) (catch Exception _ nil)))
              (recur)))))
      (catch Exception _ nil))
    ;; The pty exiting is not the session ending when tmux owns the shell: the
    ;; client detached. Only report an exit when there is no durable session.
    (let [durable? (and (:durable-name session)
                        (durable-exists? (:durable-name session)))]
      (when (and @(:on-exit session) (not durable?))
        (try ((deref (:on-exit session))
              (try (.exitValue ^Process (:proc session)) (catch Exception _ -1)))
             (catch Exception _ nil))))))

(defn- start-pty
  "Run `argv` under a pty via script(1). Returns the Process."
  [argv cwd]
  (let [script (script-path)
        full (if script
               ;; -q quiet, -f flush after every write, -e return the child's
               ;; exit status, /dev/null discards the typescript file
               [script "-q" "-f" "-e" "-c" (str/join " " argv) "/dev/null"]
               (vec argv))]
    (let [pb (-> (ProcessBuilder. (vec full))
                 (.directory (io/file (or cwd ".")))
                 (.redirectErrorStream true))]
      ;; A server process often has no TERM, or TERM=dumb, and tmux refuses to
      ;; attach without a terminal type it recognises.
      (let [term (System/getenv "TERM")]
        (when (or (nil? term) (= "dumb" term))
          (.put (.environment pb) "TERM" "xterm-256color")))
      (.start pb))))

(defn spawn
  "Start (or reattach to) a shell in `cwd`.

  opts:
    :cwd        working directory
    :id         conversation id — makes the session durable and stable
    :on-output  (fn [string])
    :on-exit    (fn [code]) — only fires when the shell itself is gone
    :command    argv, default [\"bash\" \"--login\"]"
  [{:keys [cwd id on-output on-exit command]
    :or {command ["bash" "--login"]}}]
  (let [name (when (and id (durable?)) (ensure-durable! (session-name id) cwd))
        argv (if name
               [(tmux-path) "-L" tmux-socket "attach" "-t" name]
               command)
        proc (start-pty argv cwd)
        session {:id (or id (str (java.util.UUID/randomUUID)))
                 :proc proc
                 :output (atom [])
                 :on-output (atom on-output)
                 :on-exit (atom on-exit)
                 :durable-name name
                 :cwd cwd}]
    (doto (Thread. ^Runnable (fn [] (reader-loop session)))
      (.setDaemon true)
      (.start))
    (swap! sessions assoc (:id session) session)
    session))

(defn attach!
  "Ensure a session exists for `id` and return it, creating one if needed.

  When the session already exists its output is redirected to the new client:
  the previous websocket is gone, and leaving the callback pointing at it would
  send every byte of output nowhere."
  [id cwd opts]
  (if-let [existing (get @sessions id)]
    (do (reset! (:on-output existing) (:on-output opts))
        (reset! (:on-exit existing) (:on-exit opts))
        existing)
    (spawn (merge opts {:id id :cwd cwd}))))

(defn send-input! [session s]
  (let [out (.getOutputStream ^Process (:proc session))]
    (.write out (.getBytes (str s) "UTF-8"))
    (.flush out)))

(defn drain-output [session]
  (let [chunks @(:output session)]
    (swap! (:output session) (constantly []))
    (apply str chunks)))

(defn resize!
  "Tell the pty its size. With tmux this is real — the session is resized, so a
  later reattach has the right geometry. With a bare pty we can only set the
  environment variables the shell sees next."
  [session cols rows]
  (when-let [name (:durable-name session)]
    (tmux "resize-window" "-t" name "-x" (str cols) "-y" (str rows)))
  (when-not (:durable-name session)
    (send-input! session (str "stty columns " cols " rows " rows " 2>/dev/null\n"))))

(defn close!
  "Detach this client. A durable session keeps running — that is the point of
  having one — so killing it takes `kill-session!`."
  [session]
  (try (.destroy ^Process (:proc session)) (catch Exception _ nil))
  (swap! sessions dissoc (:id session)))

(defn kill-session!
  "End a durable session and forget its client."
  [id]
  (when-let [s (get @sessions id)] (close! s))
  (when (durable?) (kill-durable! (session-name id))))

(defn describe []
  {:pty (available?) :durable (durable?)})
