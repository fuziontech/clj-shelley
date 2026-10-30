(ns shelley.tools.files
  "File tools for the agent: find_files, list_directory, read_file, write_file.

  The original serves these from the HTTP API for the UI's file picker; the
  agent needs its own equivalents, so they live here instead. Two rules:

  - Relative paths resolve against the conversation's working directory, like
    every other path-based tool. Absolute paths are honoured as given, because
    bash can already reach anywhere and pretending otherwise would be a lie.
  - A relative path that climbs out of the working directory with `..` is
    refused unless the caller opts in (`:allow-outside-working-dir?` in ctx).
    That is not a sandbox — bash has none — it is a guard against a tool call
    that meant one file and got another.

  Every output is bounded. A file the model cannot read does not make the call
  fail silently; it makes the call return an explanation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files Paths LinkOption]
           [java.nio.file.attribute BasicFileAttributes]))

;; ---------------------------------------------------------------------------
;; Paths

(defn working-dir [ctx]
  (or (some-> (:working-dir ctx) deref) (System/getProperty "user.dir")))

(defn- canonical [^File f]
  (try (.getCanonicalFile f) (catch Exception _ (.getAbsoluteFile f))))

(defn- under?
  "True when `child` is `parent` or inside it. Both must be canonical."
  [^File parent ^File child]
  (let [p (.getPath parent) c (.getPath child)]
    (or (= p c) (str/starts-with? c (str p File/separator)))))

(defn resolve-path
  "Absolute path for `path` in the conversation's working directory.

  Throws ex-info {:kind :path-escape} when a *relative* path would leave the
  working directory and ctx does not allow that."
  [ctx path]
  (let [wd (canonical (io/file (working-dir ctx)))
        f (io/file (str path))]
    (if (.isAbsolute f)
      (canonical f)
      (let [target (canonical (io/file wd (str path)))]
        (when (and (not (under? wd target))
                   (not (:allow-outside-working-dir? ctx))
                   (str/includes? (str path) ".."))
          (throw (ex-info (str "refusing to leave the working directory: " path
                               " resolves to " (.getPath target)
                               ". Use an absolute path if that is what you meant.")
                          {:kind :path-escape :path path :resolved (.getPath target)})))
        target))))

(defn- attrs [^File f]
  (try (Files/readAttributes (.toPath f) BasicFileAttributes (make-array LinkOption 0))
       (catch Exception _ nil)))

(defn- binary? [^File f]
  (when (and (.isFile f) (pos? (.length f)))
    (try
      (with-open [in (io/input-stream f)]
        (let [buf (byte-array 4096)
              n (.read in buf)]
          (boolean (some zero? (take (max 0 n) buf)))))
      (catch Exception _ false))))

;; ---------------------------------------------------------------------------
;; read_file

(def read-file-max-bytes (* 80 1024))
(def read-file-max-lines 2000)

(defn- read-lines
  "Read up to `limit` lines starting at 1-based `offset`, within a byte budget."
  [^File f offset limit]
  (let [lines (with-open [rdr (java.io.BufferedReader. (io/reader f))]
                (doall (take limit (drop (dec offset) (line-seq rdr)))))
        sb (StringBuilder.)]
    (reduce (fn [n line]
              (let [next-len (+ n (count line) 1)]
                (if (> next-len read-file-max-bytes)
                  (do (.append sb (str line "\n[stopped at the " read-file-max-bytes
                                       "-byte read limit; use offset to read further]\n"))
                      (reduced next-len))
                  (do (.append sb (str line "\n"))
                      next-len))))
            0 lines)
    (str sb)))

(defn read-file
  "Read a text file, numbered, so the model can patch precisely.

  opts: :path (required) :offset (1-based line) :limit (lines)"
  [ctx {:keys [path offset limit] :as _args}]
  (when (str/blank? (str path))
    (throw (ex-info "read_file requires a path" {:kind :bad-args})))
  (let [f (resolve-path ctx path)]
    (when-not (.exists f)
      (throw (ex-info (str "no such file: " path " (resolved to " (.getPath f) ")")
                      {:kind :not-found})))
    (when (.isDirectory f)
      (throw (ex-info (str path " is a directory; use list_directory") {:kind :is-directory})))
    (when (binary? f)
      (throw (ex-info (str path " looks like a binary file; read_file only reads text")
                      {:kind :binary})))
    (let [offset (max 1 (long (or offset 1)))
          limit (min read-file-max-lines (max 1 (long (or limit read-file-max-lines))))
          body (read-lines f offset limit)
          lines (str/split-lines body)
          shown (min limit (count lines))]
      (str (format "[%s: %d bytes, showing lines %d-%d]\n" path (.length f) offset (+ offset (dec (max 1 shown))))
           (->> lines
                (map-indexed (fn [i line] (format "%6d\t%s" (+ offset i) line)))
                (str/join "\n"))))))

;; ---------------------------------------------------------------------------
;; write_file

(defn write-file
  "Create or overwrite a file, making parent directories. :append adds to the
  end."
  [ctx {:keys [path content append] :as _args}]
  (when (str/blank? (str path))
    (throw (ex-info "write_file requires a path" {:kind :bad-args})))
  (when (nil? content)
    (throw (ex-info "write_file requires content" {:kind :bad-args})))
  (let [f (resolve-path ctx path)]
    (when (.isDirectory f)
      (throw (ex-info (str path " is a directory") {:kind :is-directory})))
    (io/make-parents f)
    (spit f (str content) :append (boolean append))
    (str "Wrote " (count (str content)) " bytes to " (.getPath f)
         (when append " (appended)"))))

;; ---------------------------------------------------------------------------
;; list_directory

(def list-dir-default-limit 200)
(def list-dir-max-limit 1000)

(defn list-directory
  "List a directory's immediate children, directories first, with sizes.
  Hidden entries are included: the model usually wants to see `.github`."
  [ctx {:keys [path limit] :as _args}]
  (let [f (resolve-path ctx (or path "."))]
    (when-not (.exists f)
      (throw (ex-info (str "no such directory: " (or path ".")) {:kind :not-found})))
    (when-not (.isDirectory f)
      (throw (ex-info (str (or path ".") " is not a directory") {:kind :not-directory})))
    (let [limit (min list-dir-max-limit (max 1 (long (or limit list-dir-default-limit))))
          files (or (.listFiles f) (make-array File 0))
          sorted (sort-by (fn [^File c] [(if (.isDirectory c) 0 1) (.getName c)]) files)
          lines (for [^File c (take limit sorted)]
                  (let [dir? (.isDirectory c)
                        a (attrs c)]
                    (str (if dir? "d " "- ")
                         (format "%10s  " (if dir? "-" (str (.length c))))
                         (.getName c)
                         (when (java.nio.file.Files/isSymbolicLink (.toPath c))
                           (str " -> " (try (.getCanonicalPath c) (catch Exception _ "?"))))
                         (when-let [mt (:lastModifiedTime a)]
                           (str "  " (str (java.time.Instant/ofEpochMilli (.toMillis mt))))))))]
      (str (.getPath f) " (" (count files) " entries)\n"
           (str/join "\n" lines)
           (when (> (count files) limit)
             (str "\n[showing first " limit " of " (count files) " entries]"))))))

;; ---------------------------------------------------------------------------
;; find_files

(def find-files-default-limit 100)
(def find-files-max-limit 1000)
(def ^:private find-files-max-visited 50000)
(def ^:private find-files-max-depth 12)
(def ^:private default-skip-dirs
  #{".git" "node_modules" "target" ".cpcache" ".clj-kondo" "__pycache__"
    ".venv" "venv" "dist" "build" ".next" ".mypy_cache" ".pytest_cache"
    ".idea" ".gradle" ".terraform" "vendor"})

(defn glob->regex
  "Translate a shell glob into a regex over `/`-separated relative paths.
  `*` does not cross `/`, `**` does, `?` is one character, `{a,b}` is an
  alternation. Anchored at both ends."
  [glob]
  (let [g (str/replace (str glob) "\\" "/")
        sb (StringBuilder.)]
    (.append sb "^")
    (loop [i 0]
      (when (< i (count g))
        (let [c (.charAt g i)
              rest (subs g i)]
          (cond
            (str/starts-with? rest "**/")
            (do (.append sb "(?:.*/)?") (recur (+ i 3)))

            (str/starts-with? rest "**")
            (do (.append sb ".*") (recur (+ i 2)))

            (= c \*)
            (do (.append sb "[^/]*") (recur (inc i)))

            (= c \?)
            (do (.append sb "[^/]") (recur (inc i)))

            (= c \{)
            (let [end (.indexOf g (int \}) i)
                  alts (when (pos? end) (str/split (subs g (inc i) end) #","))]
              (if (seq alts)
                (do (.append sb (str "(?:" (str/join "|" (map #(java.util.regex.Pattern/quote %) alts)) ")"))
                    (recur (inc end)))
                (do (.append sb "\\{") (recur (inc i)))))

            :else
            (do (.append sb (java.util.regex.Pattern/quote (str c))) (recur (inc i)))))))
    (.append sb "$")
    (re-pattern (str sb))))

(defn- walk-files
  "Walk `root` up to find-files-max-depth, skipping noisy directories. Returns
  a vector of `/`-separated relative paths, bounded by find-files-max-visited."
  [^File root include-hidden?]
  (let [out (java.util.ArrayList.)]
    (letfn [(visit [^File dir depth]
              (when (and (< depth find-files-max-depth)
                         (< (.size out) find-files-max-visited))
                (doseq [^File child (or (.listFiles dir) (make-array File 0))]
                  (let [nm (.getName child)]
                    (when-not (and (not include-hidden?) (str/starts-with? nm "."))
                      (if (.isDirectory child)
                        (when-not (contains? default-skip-dirs nm)
                          (visit child (inc depth)))
                        (.add out (str (str/replace (.toString (.relativize (.toPath root) (.toPath child)))
                                                    File/separator "/")))))))))]
      (visit root 0))
    (vec out)))

(defn find-files
  "Find files under the working directory (or `dir`).

  opts:
    :dir            where to start (default: the working directory)
    :pattern        glob on the path relative to :dir, e.g. \"**.clj\"
    :query          case-insensitive substring of the path, or of the contents
                    when :content is content-aware
    :content        \"skip\" (default) | \"path\" | \"content\" | \"only\"
                    any mode other than skip/path also greps file contents
    :limit          max results (default 100, hard max 1000)
    :include_hidden true to include dotfiles"
  [ctx {:keys [dir pattern query content limit include_hidden] :as _args}]
  (let [root (resolve-path ctx (or dir "."))]
    (when-not (.isDirectory root)
      (throw (ex-info (str (or dir ".") " is not a directory") {:kind :not-directory})))
    (let [limit (min find-files-max-limit (max 1 (long (or limit find-files-default-limit))))
          content-mode (or content "skip")
          pattern-re (when-not (str/blank? (str pattern)) (glob->regex pattern))
          q (when-not (str/blank? (str query)) (str/lower-case (str query)))
          candidates (walk-files root (boolean include_hidden))
          grep? (contains? #{"content" "only"} content-mode)
          matches (->> candidates
                       (filter (fn [rel] (or (nil? pattern-re)
                                             (boolean (re-matches pattern-re rel)))))
                       (filter (fn [rel]
                                 (or (nil? q)
                                     (str/includes? (str/lower-case rel) q)
                                     (and grep?
                                          (let [f (io/file root rel)]
                                            (and (< (.length f) 200000)
                                                 (not (binary? f))
                                                 (try (str/includes? (str/lower-case (slurp f :encoding "UTF-8")) q)
                                                      (catch Exception _ false))))))))
                       (take (inc limit))
                       vec)
          shown (take limit matches)]
      (str (if (empty? matches)
             (str "no files matching " (pr-str (or pattern query "*")) " under " (.getPath root))
             (str/join "\n" (for [rel shown]
                               (let [f (io/file root rel)]
                                 (str rel (when (.isFile f) (str "  (" (.length f) "B)")))))))
           (when (> (count matches) limit)
             (str "\n[stopped at the limit of " limit " results; narrow the pattern]"))
           "\n(" (count shown) " of " (count candidates) " files scanned)"))))
