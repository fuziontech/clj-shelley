(ns shelley.sandbox
  "Optional isolation for tool execution.

  Tool execution normally runs with the privileges of the process — that is what
  lets an agent install a package or run a test suite. Bubblewrap adds the useful
  middle ground without root: a private view of the filesystem where the
  workspace and /tmp are writable and the rest is read-only.

  Off by default (`SHELLEY_SANDBOX=1` enables it), because it changes what
  commands can do and a coding agent that cannot install anything is a different
  tool.

  Network is intentionally shared: cutting it off breaks package installs, git
  remotes, and any tool that calls a model."
  (:require [clojure.java.io :as io]))

(def bwrap-path "/usr/bin/bwrap")

(defn available?
  "True when bubblewrap is installed."
  []
  (.canExecute (io/file bwrap-path)))

(defn enabled?
  "True when sandboxing is both requested and possible."
  []
  (and (= "1" (System/getenv "SHELLEY_SANDBOX")) (available?)))

(def ^:private read-only-paths
  ["/usr" "/bin" "/sbin" "/lib" "/lib32" "/lib64" "/libexec"
   "/etc" "/opt" "/var/lib" "/nix" "/run/current-system" "/sys/devices"])

(defn- ro-binds []
  (->> read-only-paths
       (filter #(.exists (io/file ^String %)))
       (mapcat (fn [p] ["--ro-bind" p p]))))

(defn wrap-argv
  "Wrap an argument vector so it runs inside the sandbox, rooted at `cwd`.

  `extra-binds` is a sequence of [host-path guest-path] pairs to make writable
  beyond the workspace — a background-job directory, for instance, which must be
  visible or the job's exit status is written somewhere the caller cannot read.

  Returns nil when sandboxing is off, so callers keep one code path:

      (let [argv (or (sandbox/wrap-argv cwd argv extra-binds) argv)]
        (ProcessBuilder. (vec argv)))"
  ([cwd argv] (wrap-argv cwd argv []))
  ([cwd argv extra-binds]
   (when (enabled?)
     (let [home (System/getProperty "user.home")
           config (str home "/.config")]
       (vec (concat
             [bwrap-path "--die-with-parent"
              "--unshare-pid" "--unshare-ipc" "--unshare-uts"
              "--proc" "/proc" "--dev" "/dev"
              ;; Private /tmp: command debris should not outlive the command.
              "--tmpfs" "/tmp"]
             (ro-binds)
             ["--bind" (str cwd) (str cwd)]
             (when (.isDirectory (io/file config)) ["--bind" config config])
             (mapcat (fn [[src dst]] ["--bind" (str src) (str dst)]) extra-binds)
             ["--chdir" (str cwd)
              "--setenv" "HOME" home
              "--setenv" "TMPDIR" "/tmp"]
             ["--"]
             (map str argv)))))))

(defn wrap-command
  "Wrap a shell command string. Convenience for callers that have a command
  rather than an argv; returns a command vector or nil."
  ([cwd command] (wrap-command cwd command []))
  ([cwd command extra-binds]
   (wrap-argv cwd ["bash" "--login" "-c" command] extra-binds)))

(defn describe
  "Summary for settings screens."
  []
  {:available (available?) :enabled (enabled?) :path bwrap-path})
