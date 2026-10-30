(ns shelley.server.git-api
  "Git endpoints for the UI's source panel."
  (:require [cheshire.core :as json]
            [shelley.git :as git]))

(defn- json-resp [status body]
  {:status status :headers {"content-type" "application/json"}
   :body (json/generate-string body)})

(defn status [{:keys [params]}]
  (let [cwd (or (:cwd params) (System/getProperty "user.dir"))]
    (if-let [st (git/status cwd)]
      (json-resp 200 (assoc st :changed (git/changed-files cwd)))
      (json-resp 200 {:repo_root nil :branch nil}))))

(defn diff [{:keys [params]}]
  (let [cwd (or (:cwd params) (System/getProperty "user.dir"))
        path (:path params)]
    (json-resp 200 {:diff (or (git/diff cwd path) "")})))

(def routes
  [["/api/git/status" {:get status}]
   ["/api/git/diff" {:get diff}]])
