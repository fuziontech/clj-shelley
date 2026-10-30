(ns shelley.gitstate
  "The git state of a working directory, cheaply and repeatedly.

  The conversation list shows, for every conversation, which repository it is
  working in and where its HEAD is. That is one `git` process per row per
  refresh if done naively, which for a list of fifty conversations is a storm of
  processes every couple of seconds. So: run the commands, cache the result
  briefly, and let the caller decide how fresh it needs to be.

  Nothing here throws. A directory that is not a repository is a normal answer,
  not an error."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util.concurrent TimeUnit)))

(def ^:private ttl-ms 2000)

(defonce ^:private cache (atom {}))

(defn- git
  "Run git in `dir`. Returns {:exit n :out s}, never throws."
  [dir & args]
  (try
    (let [pb (doto (ProcessBuilder. (into ["git" "--no-pager"] args))
               (.directory (io/file (str dir)))
               (.redirectErrorStream true))
          p (.start pb)
          out (future (slurp (.getInputStream p)))
          done (.waitFor p 5 TimeUnit/SECONDS)]
      (if done
        {:exit (.exitValue p) :out (str/trim (str @out))}
        (do (.destroyForcibly p) {:exit -1 :out ""})))
    (catch Exception _ {:exit -1 :out ""})))

(defn- ok? [{:keys [exit]}] (zero? exit))

(defn root
  "The repository root containing `dir`, or nil."
  [dir]
  (let [{:keys [out]} (git dir "rev-parse" "--show-toplevel")]
    (when (and (seq out) (.isDirectory (io/file out))) out)))

(defn head
  "Current commit as {:commit short-sha :subject text}, or nil.

  Two calls rather than one with a separator: a commit subject may contain any
  character, so the only reliable separator is the command boundary."
  [dir]
  (let [{sha :out} (git dir "log" "-1" "--pretty=%h")]
    (when (seq sha)
      {:commit sha
       :subject (:out (git dir "log" "-1" "--pretty=%s"))})))

(defn branch
  "Current branch name, or nil when detached."
  [dir]
  (let [{:keys [out]} (git dir "rev-parse" "--abbrev-ref" "HEAD")]
    (when (and (seq out) (not= out "HEAD")) out)))

(defn dirty-count [dir]
  (let [{:keys [out]} (git dir "status" "--porcelain")]
    (if (str/blank? out) 0 (count (str/split-lines out)))))

(defn state
  "Everything the UI shows about a directory's git state. Cached for a moment.

  A stale entry is recomputed here rather than recursed on: recursing re-enters
  the same stale lookup and spins forever, which wedges whichever request
  thread asked."
  [dir]
  (let [dir (str (or dir "."))
        now (System/currentTimeMillis)
        cached (get @cache dir)]
    (if (and cached (< (- now (:at cached)) ttl-ms))
      (:value cached)
      (let [r (if-let [repo-root (root dir)]
                (merge {:git_repo_root repo-root
                        :git_branch (branch dir)}
                       (when-let [h (head dir)]
                         {:git_commit (:commit h) :git_subject (:subject h)})
                       {:git_dirty (dirty-count dir)})
                {:git_repo_root nil :git_branch nil
                 :git_commit nil :git_subject nil :git_dirty 0})]
        (swap! cache assoc dir {:at now :value r})
        r))))

(defn forget!
  "Drop the cached state for a directory — call after a commit or checkout."
  ([] (reset! cache {}))
  ([dir] (swap! cache dissoc (str dir))))
