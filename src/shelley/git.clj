(ns shelley.git
  "Git introspection for the workspace.

  The original tracks git state per conversation so the UI can show the branch,
  the commit, and a diff of what the agent changed. It also caches HEAD lookups
  because a conversation list with 500 rows would otherwise shell out 500 times.

  Everything here is best-effort: a workspace that is not a git repository is
  normal, and must not produce errors anywhere upstream."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- git [dir & args]
  (try
    ;; --no-pager must precede the subcommand, or git echoes it into the output.
    (apply sh/sh "git" "--no-pager" "-C" dir args)
    (catch Exception _ nil)))

(defn- ok? [res] (and res (zero? (:exit res))))

(defn- out [res] (some-> res :out str/trim))

(defn repo-root [dir]
  (let [r (git dir "rev-parse" "--show-toplevel")]
    (when (ok? r) (not-empty (out r)))))

(defn worktree-root [dir]
  (let [r (git dir "rev-parse" "--show-toplevel")]
    (when (ok? r) (not-empty (out r)))))

(defn head
  "Current commit and subject, or nil outside a repository."
  [dir]
  (let [r (git dir "log" "-1" "--format=%H%n%s")]
    (when (ok? r)
      (let [lines (str/split-lines (out r))]
        {:commit (first lines) :subject (second lines)}))))

(defn branch [dir]
  (let [r (git dir "rev-parse" "--abbrev-ref" "HEAD")]
    (when (ok? r) (let [b (out r)] (when-not (str/blank? b) b)))))

(defn dirty? [dir]
  (let [r (git dir "status" "--porcelain")]
    (and (ok? r) (not (str/blank? (out r))))))

(defn status [dir]
  (when-let [root (repo-root dir)]
    (merge {:repo_root root :worktree_root root :branch (branch dir) :dirty (dirty? dir)}
           (head dir))))

(defn changed-files
  "Files differing from HEAD, with their status letter."
  [dir]
  (let [r (git dir "status" "--porcelain")]
    (when (ok? r)
      (->> (str/split-lines (out r))
           (remove str/blank?)
           ;; Porcelain v1 is "XY PATH": two status columns, a space, then the
           ;; path. Splitting on whitespace instead of slicing a fixed offset
           ;; survives a status column that git left out and keeps the whole
           ;; path when it contains spaces.
           (mapv (fn [line]
                   {:status (str/trim (subs line 0 (min 2 (count line))))
                    :path (str/trim (last (str/split line #"\s+" 3)))}))))))

(defn diff
  "Unified diff against HEAD, optionally for one path."
  [dir path]
  (let [r (apply git dir "diff" "HEAD" (when path [path]))]
    (when (ok? r) (:out r))))

(defn file-diff [dir path] (diff dir path))

(defn commit-all!
  "Stage everything and commit. Used by commit-highlight style tooling."
  [dir message]
  (when (ok? (git dir "add" "-A"))
    (ok? (git dir "commit" "-m" message))))

(defn worktree-root-of [dir]
  (or (worktree-root dir) dir))
