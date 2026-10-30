(ns shelley.server.jobs
  "Background jobs of one conversation: list them, tail them, kill one.

  Two sources have to be merged. The live registry in memory knows the tail and
  whether the process is still there; the database knows what was started before
  the last restart. A job present only in the database is shown as not running,
  because a process that outlived the server is not something this server can
  pipe output from any more."
  (:require [clojure.string :as str]
            [shelley.db :as db]
            [shelley.server.http :as http]
            [shelley.tools.bash-job :as bash-job])
  (:import (java.util.concurrent TimeUnit)))

(defn- live-jobs [conv-id]
  (try (bash-job/list-jobs conv-id) (catch Throwable _ [])))

(defn jobs-of [conv-id]
  (let [live (into {} (map (fn [j] [(:job-id j) j]) (live-jobs conv-id)))
        stored (try (db/list-running-background-jobs conv-id) (catch Throwable _ []))
        ids (distinct (concat (keys live) (map :job-id stored)))]
    (mapv (fn [id]
            (let [l (get live id)
                  s (some #(when (= id (:job-id %)) %) stored)]
              (http/compact-map
               {"job_id" id
                "command" (or (:command l) (:command s))
                "tail" (:tail l)
                "running" (if l (boolean (:running l)) (not (:exited s)))
                "pgid" (:pgid l)
                "pid" (:pid s)
                "log_path" (or (:log-path l) (:log-path s))
                "started_at" (http/iso-time (or (:started-at l) (:started-at s)))})))
          ids)))

(defn handler-list [req]
  (let [conv-id (http/path-param req :id)]
    (if (nil? (db/get-conversation conv-id))
      (http/not-found "conversation not found")
      (http/ok (jobs-of conv-id)))))

(defn- signal-group!
  "SIGTERM the job's process group. The wrapper stays alive to record the exit
  status, so this is a polite kill by design: the agent's own notice arrives
  afterwards through the normal path."
  [pgid]
  (when pgid
    (try
      (let [p (.start (ProcessBuilder. ["kill" "-TERM" "--" (str "-" pgid)]))]
        (zero? (.waitFor p 5 TimeUnit/SECONDS)))
      (catch Exception _ false))))

(defn handler-kill [req]
  (let [conv-id (http/path-param req :id)
        job-id (or (http/path-param req :id-2) (http/path-param req :jobID))]
    (if (nil? (db/get-conversation conv-id))
      (http/not-found "conversation not found")
      (let [job (some #(when (= job-id (:job_id %)) %) (jobs-of conv-id))]
        (cond
          (nil? job)
          (http/not-found (str "no running background job " job-id " in this conversation"))

          (not (:running job))
          (do (db/mark-background-job-exited! job-id)
              (http/ok {"status" "already_exited" "job_id" job-id}))

          :else
          (let [outcome (if-let [live (bash-job/find-job job-id)]
                          ;; The live registry knows the process group, so let
                          ;; the tool layer do the killing: it also reports the
                          ;; exit to the agent through the normal notice.
                          (try (bash-job/kill! live) (catch Throwable _ :error))
                          (if (signal-group! (or (:pgid job) (:pid job))) :killed :error))]
            (db/mark-background-job-exited! job-id)
            (http/ok {"status" (case outcome
                                 :killed "killed"
                                 :gone "already_exited"
                                 "error")
                      "job_id" job-id})))))))

(def routes
  [["/api/conversation/:id/background-jobs" {:get handler-list}]
   ["/api/conversation/:id/background-jobs/:id/kill" {:post handler-kill}]
   ["/api/conversations/:id/background-jobs" {:get handler-list}]
   ["/api/conversations/:id/background-jobs/:id/kill" {:post handler-kill}]])
