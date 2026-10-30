(ns shelley.sandbox
  "Optional isolation for tool execution.

  In the original, tool execution is constrained; here `bash` runs with the
  privileges of the process unless you ask for a sandbox. Bubblewrap gives us
  the useful part of that — a private view of the filesystem where the workspace
  and /tmp are writable and everything else is read-only — without needing root.

  Disabled by default (`SHELLEY_SANDBOX=1` enables it) because it changes what
  commands can do, and a coding agent that cannot install a package is a
  different tool."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn available? []
  (let [f (io/file "/usr/bin/bwrap")]
    (.canExecute f)))

(defn enabled? []
  (and (= "1" (System/getenv "SHELLEY_SANDBOX")) (available?)))

(defn- read-only-binds []
  (->> ["/usr" "/bin" "/sbin" "/lib" "/lib32" "/lib64" "/etc" "/opt" "/var/lib"
        "/nix" "/run/current-system"]
       (filter #(.exists (io/file %)))
       (mapcat (fn [p] ["--ro-bind" p p]))))

(defn wrap-command
  "Wrap a shell command so it runs in a sandbox rooted at `cwd`.

  Returns a command vector for ProcessBuilder, or nil when sandboxing is off.

  The workspace is the only writable host path besides /tmp and the user's home
  config directory (agents legitimately write tool caches). Network is shared:
  cutting it off breaks package installation, git remotes, and the model calls
  tools make, which makes the agent useless for real work."
  [cwd command]
  (when (enabled?)
    (vec (concat ["bwrap"
                  "--die-with-parent"
                  "--unshare-pid"
                  "--unshare-ipc"
                  "--unshare-uts"
                  "--proc" "/proc"
                  "--dev" "/dev"
                  ;; A private /tmp: agents leave debris behind and it should not
                  ;; outlive the session.
                  "--tmpfs" "/tmp"]
                 (read-only-binds)
                 ["--bind" (str cwd) (str cwd)
                  "--bind" (str (System/getProperty "user.home") "/.config")
                  (str (System/getProperty "user.home") "/.config")
                  "--chdir" (str cwd)
                  "--setenv" "HOME" (System/getProperty "user.home")
                  "--"]
                 ["bash" "--login" "-c" command]))))

(defn describe
  "Human-readable summary for the UI/settings screen."
  []
  {:available (available?)
   :enabled (enabled?)})
