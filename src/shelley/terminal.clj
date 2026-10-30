(ns shelley.terminal
  "Interactive terminal sessions for the UI's exec pane.

  A terminal needs a pty, not a pipe: programs decide how to behave based on
  whether they are talking to one (line buffering, colours, job control). The JVM
  cannot allocate a pty portably, so we borrow the system utility that exists
  precisely for this — `script` — which runs the shell under a pty and copies
  bytes both ways.

  Frames are JSON over the websocket: {:type :input|:resize|:output|:exit}."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- which [cmd]
  (some (fn [dir]
          (let [f (io/file dir cmd)]
            (when (.canExecute f) (.getPath f))))
        (str/split (or (System/getenv "PATH") "") #":")))

(defn available?
  "True when we can offer a real terminal on this host."
  []
  (some? (which "script")))

(defonce sessions (atom {}))

(defn- reader-loop [session]
  (let [in (.getInputStream (:proc session))
        buf (byte-array 4096)]
    (try
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (let [s (String. buf 0 n "UTF-8")]
              (swap! (:output session) conj s)
              (when-let [f (:on-output session)]
                (try (f s) (catch Exception _ nil))))
            (recur))))
      (catch Exception _ nil))
    (when-let [f (:on-exit session)]
      (try (f (.exitValue (:proc session))) (catch Exception _ (f -1))))))

(defn spawn
  "Start a shell in `cwd`. Returns a session map.

  opts: :on-output (fn [string]), :on-exit (fn [code]),
        :command (vector, default a login bash)."
  [{:keys [cwd on-output on-exit command]
    :or {command ["bash" "--login"]}}]
  (let [script (which "script")
        argv (if script
               ;; -q quiet, -f flush, -c command, /dev/null typescript sink
               [script "-q" "-f" "-c" (str/join " " command) "/dev/null"]
               command)
        pb (doto (ProcessBuilder. (vec argv))
             (.directory (io/file (or cwd ".")))
             (.redirectErrorStream true))
        proc (.start pb)
        session {:id (str (java.util.UUID/randomUUID))
                 :proc proc
                 :output (atom [])
                 :on-output on-output
                 :on-exit on-exit
                 :pty? (some? script)
                 :cwd cwd}]
    (doto (Thread. ^Runnable (fn [] (reader-loop session)))
      (.setDaemon true)
      (.start))
    (swap! sessions assoc (:id session) session)
    session))

(defn send-input! [session s]
  (let [out (.getOutputStream ^Process (:proc session))]
    (.write out (.getBytes (str s) "UTF-8"))
    (.flush out)))

(defn drain-output [session]
  (let [chunks @(:output session)]
    (swap! (:output session) (constantly []))
    (apply str chunks)))

(defn resize!
  "best effort. `script` does not forward resizes, so this is advisory only:
  we set the COLUMNS/LINES the shell sees on the next command."
  [session cols rows]
  (send-input! session (str "stty columns " cols " rows " rows " 2>/dev/null\n")))

(defn close! [session]
  (try (.destroy ^Process (:proc session)) (catch Exception _ nil))
  (swap! sessions dissoc (:id session)))
