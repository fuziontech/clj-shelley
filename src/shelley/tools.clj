(ns shelley.tools
  "The tool registry.

  A tool is a map:

    {:name         \"bash\"
     :description  \"...\"
     :input-schema {...JSON Schema...}
     :default-on?  true
     :handler      (fn [ctx args] -> result)}

  A handler returns either a string (becomes a text block) or a map
  {:content [block...] :is-error boolean}. Anything it throws becomes an error
  tool-result, which the model sees and can recover from.

  `ctx` carries: :working-dir (atom of absolute path), :conversation-id,
  :emit (fn [event]) for progress, :run-subagent (fn [{:keys [prompt tools toolset model]}])
  and :base-url."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [shelley.llm :as llm])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; bash

(def ^:private bash-timeout-ms 120000)

(defn- drain
  "Read a stream into a StringBuilder on a background thread, emitting progress."
  [^java.io.InputStream in ^StringBuilder sb emit]
  (doto (Thread. (fn []
                   (with-open [r (io/reader in)]
                     (let [buf (char-array 4096)]
                       (loop []
                         (let [n (.read r buf)]
                           (when (pos? n)
                             (let [s (String. buf 0 n)]
                               (locking sb (.append sb s))
                               (when emit (emit {:type :tool-progress :text s})))
                             (recur))))))))
    (.setDaemon true)
    (.start)))

(defn run-bash
  "Run a shell command. Returns {:content ... :is-error bool}.

  The working directory is wherever the conversation last moved to, so this is
  stateful per conversation, matching the original's bash tool."
  [{:keys [working-dir emit]} {:keys [command timeout_ms] :as _args}]
  (let [dir (or @working-dir (System/getProperty "user.dir"))
        timeout (or timeout_ms bash-timeout-ms)
        pb (doto (ProcessBuilder. ["bash" "--login" "-c" command])
             (.directory (File. dir))
             (.redirectErrorStream true))
        env (.environment pb)]
    ;; Non-interactive, no pagers, and make sure common tool chains are on PATH.
    (.put env "PAGER" "cat")
    (.put env "GIT_PAGER" "cat")
    (.put env "DEBIAN_FRONTEND" "noninteractive")
    (let [proc (.start pb)
          sb (StringBuilder.)]
      (drain (.getInputStream proc) sb emit)
      (let [finished (.waitFor proc timeout TimeUnit/MILLISECONDS)]
        (when-not finished
          (.destroyForcibly proc)
          (.waitFor proc 5 TimeUnit/SECONDS))
        (Thread/sleep 60) ; let the drain thread finish flushing
        (let [code (when finished (.exitValue proc))
              out (locking sb (str sb))
              out (if (> (count out) 40000)
                    (str (subs out 0 20000) "\n... [truncated " (- (count out) 40000) " chars] ...\n"
                         (subs out (- (count out) 20000)))
                    out)
              failed? (or (not finished) (and code (not (zero? code))))]
          {:content [(llm/text-block
                      (str (cond (not finished) (str "[timed out after " timeout "ms; process killed]\n")
                                 (not (zero? code)) (str "[exit " code "]\n")
                                 :else "")
                           (if (str/blank? out) "(no output)" out)))]
           :is-error failed?})))))

;; ---------------------------------------------------------------------------
;; patch — precise file edits

(defn- read-file [^File f]
  (slurp f))

(defn- unique-match?
  "True when `needle` occurs exactly once in `haystack`."
  [haystack needle]
  (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle)) haystack))))

(defn- apply-replace [text {:keys [oldText newText]}]
  (cond
    (nil? oldText) (throw (ex-info "replace requires oldText" {}))
    (not (str/includes? text oldText))
    (throw (ex-info (str "oldText not found in file. First 200 chars of oldText: "
                         (subs (str oldText) 0 (min 200 (count (str oldText)))))
                    {:kind :patch-no-match}))
    (not (unique-match? text oldText))
    (throw (ex-info "oldText appears more than once; add surrounding context so the edit is unambiguous"
                    {:kind :patch-ambiguous}))
    :else (str/replace-first text oldText (str newText))))

(defn- resolve-path [working-dir path]
  (let [f (io/file path)]
    (if (.isAbsolute f) f (io/file @working-dir path))))

(defn run-patch
  "Edit a file. Mirrors the original patch tool: a list of operations applied in
  order, each replacing a unique snippet, plus whole-file writes and appends."
  [{:keys [working-dir]} {:keys [operations path]}]
  (let [ops operations]
    (when-not (seq ops)
      (throw (ex-info "patch requires a non-empty :operations list" {})))
    (let [touched (volatile! [])]
      (doseq [{:keys [path operation oldText newText] :as op} ops]
        (let [file (resolve-path working-dir path)
              _ (vswap! touched conj (.getPath file))
              current (if (.exists file) (read-file file) "")]
          (case operation
            "overwrite" (do (io/make-parents file) (spit file (str newText)))
            "append_eof" (do (io/make-parents file)
                             (spit file (str current (if (and (pos? (count current))
                                                              (not (str/ends-with? current "\n"))) "\n" "") newText)))
            "prepend_bof" (do (io/make-parents file) (spit file (str newText current)))
            "replace" (do (when-not (.exists file)
                            (throw (ex-info (str "cannot replace in missing file: " path) {})))
                          (spit file (apply-replace current op)))
            (throw (ex-info (str "unknown operation: " operation
                                 " (expected replace, append_eof, prepend_bof, overwrite)")
                            {})))))
      {:content [(llm/text-block (str "Applied " (count ops) " operation(s) to:\n"
                                      (str/join "\n" (distinct @touched))))]})))

;; ---------------------------------------------------------------------------
;; change_dir

(defn run-change-dir [{:keys [working-dir]} {:keys [path]}]
  (let [target (-> (if (.isAbsolute (io/file (or path ".")))
                     (io/file (or path "."))
                     (io/file (or @working-dir ".") (or path ".")))
                   .getCanonicalFile)]
    (when-not (.isDirectory target)
      (throw (ex-info (str "not a directory: " (.getPath target)) {})))
    (reset! working-dir (.getPath target))
    {:content [(llm/text-block (str "Working directory is now " (.getPath target)))]}))

;; ---------------------------------------------------------------------------
;; read_image

(def ^:private image-types
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "webp" "image/webp"})

(defn run-read-image [{:keys [working-dir]} {:keys [path]}]
  (let [file (resolve-path working-dir path)]
    (when-not (.exists file)
      (throw (ex-info (str "no such file: " path) {})))
    (let [ext (-> (.getName file) (str/lower-case) (str/split #"\.") last)
          media (get image-types ext)]
      (when-not media
        (throw (ex-info (str "unsupported image type: " ext) {})))
      {:content [{:type :image
                  :media-type media
                  :data (.encodeToString (java.util.Base64/getEncoder)
                                         (java.nio.file.Files/readAllBytes (.toPath file)))}]})))

;; ---------------------------------------------------------------------------
;; llm_one_shot — a cheap side call to another model

(defn run-llm-one-shot [{:keys [base-url]} {:keys [prompt model system files]}]
  (let [prompt (str (when (seq files)
                      (str "Context files:\n"
                           (str/join "\n\n" (for [f files]
                                              (str "--- " (:path f) " ---\n" (:content f)))) "\n\n"))
                    prompt)
        resp (llm/complete {:model (or model "openai/gpt-6.1-sol")
                            :system (cond-> [] (seq system) (conj system))
                            :messages [(llm/user-message prompt)]
                            :max-tokens 8000
                            :base-url base-url})]
    {:content (vec (filter llm/text? (:content resp)))}))

;; ---------------------------------------------------------------------------
;; output_iframe — hand a rendered artefact to the UI

(def iframe-dir
  (io/file (System/getProperty "user.home") ".clj-shelley" "iframes"))

(defn run-output-iframe [_ {:keys [html title files]}]
  (.mkdirs iframe-dir)
  (let [id (str (java.util.UUID/randomUUID))
        f (io/file iframe-dir (str id ".html"))]
    (spit f (str html))
    (doseq [{:keys [name content]} files]
      (let [target (io/file iframe-dir (str id "-" (str/replace name #"/" "_")))]
        (spit target (str content))))
    {:content [(llm/text-block (str "Rendered " (or title "output") ". See it at /iframe/" id))]
     :ui {:type :iframe :url (str "/iframe/" id) :title (or title "Output")}}))

;; ---------------------------------------------------------------------------
;; subagent — a nested conversation with its own context

(defn run-subagent
  [{:keys [run-subagent conversation-id]} {:keys [slug prompt toolset model] :as args}]
  (if-not run-subagent
    {:content [(llm/text-block "subagents are not available in this session")] :is-error true}
    (let [result (run-subagent {:parent-id conversation-id
                                :slug (or slug (str "sub-" (subs (str (java.util.UUID/randomUUID)) 0 8)))
                                :prompt prompt
                                :toolset (or toolset :default)
                                :model model})]
      {:content [(llm/text-block (str "Subagent `" (:slug result) "` finished.\n\n"
                                      (or (:text result) "(no output)")))]})))

;; ---------------------------------------------------------------------------
;; Registry

(def all-tools
  (into {}
        (map (fn [t] [(:name t) t]))
        [{:name "bash"
          :description "Run a shell command with bash. The working directory is whatever change_dir last set. Commands time out after 120s by default. Output is combined stdout+stderr."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:command {:type "string"
                                                :description "The command to run."}
                                      :timeout_ms {:type "integer"
                                                   :description "Timeout in milliseconds (max 600000)."}}
                         :required ["command"]}
          :handler run-bash}

         {:name "patch"
          :description "Make precise edits to files. Prefer this over rewriting whole files. Operations: replace (oldText must appear exactly once), append_eof, prepend_bof, overwrite. Paths are relative to the working directory."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:operations
                                      {:type "array"
                                       :items {:type "object"
                                               :properties {:path {:type "string"}
                                                            :operation {:type "string"
                                                                        :enum ["replace" "append_eof" "prepend_bof" "overwrite"]}
                                                            :oldText {:type "string"}
                                                            :newText {:type "string"}}
                                               :required ["path" "operation"]}}}
                         :required ["operations"]}
          :handler run-patch}

         {:name "change_dir"
          :description "Change the working directory used by bash and path-based tools."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:path {:type "string"}}
                         :required ["path"]}
          :handler run-change-dir}

         {:name "read_image"
          :description "Read an image file (png, jpg, gif, webp) so you can see it."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:path {:type "string"}}
                         :required ["path"]}
          :handler run-read-image}

         {:name "llm_one_shot"
          :description "Send a single prompt to another model and get its answer. Useful for a cheap second opinion or summarizing a large body of text."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:prompt {:type "string"}
                                      :model {:type "string"}
                                      :system {:type "string"}}
                         :required ["prompt"]}
          :handler run-llm-one-shot}

         {:name "output_iframe"
          :description "Render HTML (charts, diagrams, interactive demos) in the user's UI."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:html {:type "string"}
                                      :title {:type "string"}
                                      :files {:type "array"
                                              :items {:type "object"
                                                      :properties {:name {:type "string"}
                                                                   :content {:type "string"}}}}}
                         :required ["html"]}
          :handler run-output-iframe}

         {:name "subagent"
          :description "Spawn a subagent with its own context window to do a self-contained job. Returns only its final message."
          :default-on? false
          :input-schema {:type "object"
                         :properties {:slug {:type "string"}
                                      :prompt {:type "string"}
                                      :toolset {:type "string" :enum ["default" "readonly"]}
                                      :model {:type "string"}}
                         :required ["prompt"]}
          :handler run-subagent}]))

(defn tool-defs [toolset]
  (let [names (case toolset
                :readonly #{"bash" "patch" "change_dir" "read_image"}
                nil)]
    (->> (vals all-tools)
         (filter :default-on?)
         (filter #(or (nil? names) (names (:name %))))
         (mapv llm/->api-tool))))

(defn keywordize-args
  "Tool arguments arrive as JSON. Whether they are keywordised upstream is not
  something a handler should have to know, so normalise at the boundary."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) (keywordize-args v)]) x))
    (vector? x) (mapv keywordize-args x)
    :else x))

(defn execute
  "Run one tool-use block. Always returns a tool-result block."
  [ctx block]
  (let [{:keys [id name input]} block
        tool (get all-tools name)]
    (try
      (if-not tool
        {:type :tool-result :tool-use-id id :is-error true
         :content [(llm/text-block (str "unknown tool: " name))]}
        (let [res (try ((:handler tool) ctx (keywordize-args (or input {})))
                       (catch Exception e
                         {:is-error true
                          :content [(llm/text-block (str (.getMessage e)))]}))]
          (cond-> {:type :tool-result
                   :tool-use-id id
                   :is-error (boolean (:is-error res))
                   :content (cond
                              (string? res) [(llm/text-block res)]
                              (vector? res) res
                              :else (vec (:content res)))}
            (:ui res) (assoc :ui (:ui res)))))
      (catch Throwable t
        {:type :tool-result :tool-use-id id :is-error true
         :content [(llm/text-block (str "tool error: " (.getMessage t)))]}))))
