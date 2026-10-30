(ns shelley.server.files-api
  "Workspace browsing endpoints: find files, list a directory, read and write a
  file, and serve images that a message refers to.

  These are read-mostly views over the same filesystem the agent's tools touch,
  so path handling is deliberately conservative: everything is resolved against
  an allowed root and anything that escapes it is refused."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private max-read-bytes
  "Refuse to hand the browser a file it cannot render anyway."
  2000000)

(defn- ->path [root p]
  (.getCanonicalFile (io/file (or root ".") (or p "."))))

(defn within-root?
  "True when `p` resolves inside `root`. Prevents ../ escapes."
  [root p]
  (let [r (.getCanonicalPath (io/file root))
        c (.getCanonicalPath (io/file p))]
    (or (= r c) (str/starts-with? c (str r java.io.File/separator)))))

(defn- json-resp [status body]
  {:status status :headers {"content-type" "application/json"}
   :body (json/generate-string body)})

;; ---------------------------------------------------------------------------

(def ^:private ignored-dirs
  #{".git" "node_modules" ".cpcache" "target" ".venv" "__pycache__" ".idea" ".gradle"})

(defn find-files
  "Fuzzy-ish path search under `cwd`. Ranks exact basename hits first, then
  substring matches, then prefix matches, and caps the result."
  [{:keys [params]}]
  (let [root (or (:cwd params) (System/getProperty "user.dir"))
        q (str/lower-case (str (or (:q params) "")))
        limit (Integer/parseInt (str (or (:limit params) "50")))
        root-file (io/file root)]
    (if-not (.isDirectory root-file)
      (json-resp 400 {:error "cwd is not a directory"})
      (let [matches (atom [])
            walk (fn walk [^java.io.File dir depth]
                   (when (and (< depth 12) (< (count @matches) 400))
                     (doseq [f (.listFiles dir)]
                       (when (< (count @matches) 400)
                         (when-not (contains? ignored-dirs (.getName f))
                           (let [rel (subs (.getPath f) (inc (count (.getPath root-file))))]
                             (cond
                               (.isDirectory f) (walk f (inc depth))
                               (or (str/blank? q) (str/includes? (str/lower-case rel) q))
                               (swap! matches conj
                                      {:path rel
                                       :score (cond
                                                (= (str/lower-case (.getName f)) q) 0
                                                (str/includes? (str/lower-case (.getName f)) q) 1
                                                :else 2)}))))))))]
        (walk root-file 0)
        (json-resp 200 {:files (->> @matches (sort-by (juxt :score :path))
                                    (take limit) (mapv :path))})))))

(defn list-directory [{:keys [params]}]
  (let [root (or (:cwd params) (System/getProperty "user.dir"))
        dir (->path root (:path params))]
    (if-not (within-root? root dir)
      (json-resp 403 {:error "path escapes the workspace"})
      (if-not (.isDirectory dir)
        (json-resp 404 {:error "no such directory"})
        (json-resp 200
                   {:path (.getPath dir)
                    :entries (->> (.listFiles dir)
                                  (remove #(contains? ignored-dirs (.getName %)))
                                  (sort-by (juxt #(not (.isDirectory %)) #(.getName %)))
                                  (mapv (fn [f] {:name (.getName f)
                                                 :path (subs (.getPath f)
                                                             (min (count (.getPath f))
                                                                  (inc (count (.getPath dir)))))
                                                 :dir (.isDirectory f)
                                                 :size (when (.isFile f) (.length f))})))})))))

(defn read-file [{:keys [params]}]
  (let [root (or (:cwd params) (System/getProperty "user.dir"))
        f (->path root (:path params))]
    (cond
      (not (within-root? root f)) (json-resp 403 {:error "path escapes the workspace"})
      (not (.isFile f)) (json-resp 404 {:error "no such file"})
      (> (.length f) max-read-bytes) (json-resp 413 {:error "file too large to display"})
      :else (json-resp 200 {:path (.getPath f)
                            :content (slurp f)
                            :size (.length f)}))))

(defn write-file [{:keys [body]}]
  (let [{:keys [cwd path content]} (json/parse-string (slurp body) true)
        root (or cwd (System/getProperty "user.dir"))
        f (->path root path)]
    (cond
      (not (within-root? root f)) (json-resp 403 {:error "path escapes the workspace"})
      :else (do (io/make-parents f)
                (spit f (str content))
                (json-resp 200 {:path (.getPath f) :size (.length f)})))))

(defn serve-image
  "Serve a file inline, with a content type guessed from the extension."
  [{:keys [params]}]
  (let [root (or (:cwd params) (System/getProperty "user.dir"))
        f (->path root (:path params))
        name (str/lower-case (.getName f))
        type (cond
               (str/ends-with? name ".png") "image/png"
               (or (str/ends-with? name ".jpg") (str/ends-with? name ".jpeg")) "image/jpeg"
               (str/ends-with? name ".gif") "image/gif"
               (str/ends-with? name ".webp") "image/webp"
               (str/ends-with? name ".svg") "image/svg+xml"
               :else nil)]
    (if (and type (.isFile f) (within-root? root f))
      {:status 200 :headers {"content-type" type} :body (io/input-stream f)}
      (json-resp 404 {:error "no such image"}))))

(def routes
  [["/api/find-files" {:get find-files}]
   ["/api/list-directory" {:get list-directory}]
   ["/api/file" {:get read-file :put write-file}]
   ["/api/workspace/image" {:get serve-image}]])
