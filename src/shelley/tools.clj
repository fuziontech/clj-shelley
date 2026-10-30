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
  and :base-url. Two more keys are optional and enable capabilities rather than
  failing without them:

    :inject-message  (fn [message]) — put a user message into the running
                     conversation. Background jobs use it to deliver their
                     completion notice; without it the job still emits
                     :background-job-completion, so a UI can show the result.
    :messages        the conversation history (vector, atom or fn), used by
                     compact_in_place to index what it is allowed to compact.

  Every handler also receives :tool-use-id, set by `execute` from the block, so
  an asynchronous handler can tag the events it emits later."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [shelley.llm :as llm]
            [shelley.tools.bash-job :as bash-job]
            [shelley.tools.browse :as browse]
            [shelley.tools.compact :as compact]
            [shelley.tools.files :as files]
            [shelley.tools.mcp :as mcp]
            [shelley.tools.message-user :as message-user]
            [shelley.tools.skills :as skills])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; bash

(def ^:private bash-timeout-ms 60000)
(def ^:private bash-output-limit 40000)

(defn- truncate-middle
  "Keep both ends of a long output: the first lines usually say what ran, the
  last lines say how it ended."
  [^String out limit]
  (if (<= (count out) limit)
    out
    (let [half (quot limit 2)]
      (str (subs out 0 half)
           "\n... [" (- (count out) limit) " chars omitted; read the log file for the rest] ...\n"
           (subs out (- (count out) half))))))

(defn- bash-result
  "Foreground result for a finished job: output, exit code, and a nudge toward
  the background tool when output was cut."
  [job]
  (let [log (io/file (:log-path job))
        out (if (.exists log) (slurp log) "")
        code (bash-job/exit-code job)
        shown (truncate-middle out bash-output-limit)]
    {:content [(llm/text-block
                (str (when (and code (not (zero? code))) (str "[exit " code "]\n"))
                     (if (str/blank? shown) "(no output)" shown)))]
     :is-error (boolean (and code (not (zero? code))))}))

(defn run-bash
  "Run a shell command.

  Every command becomes a background *job* under the hood (see
  shelley.tools.bash-job): it writes to a log file and records its own exit
  status. That is what makes the two cases below one code path —

  - it finishes inside `timeout_ms` (default 60s): you get its output and exit
    code, exactly like a plain shell call;
  - it is still running: control returns with a job id and the log tail, and
    the completion arrives as a new message when it exits. Pass
    `background: true` to get that behaviour immediately.

  Commands run inside the bubblewrap sandbox when SHELLEY_SANDBOX=1."
  [{:keys [working-dir emit inject-message conversation-id] :as ctx}
   {:keys [command timeout_ms background] :as _args}]
  (let [dir (or (some-> working-dir deref) (System/getProperty "user.dir"))
        timeout (long (or timeout_ms bash-timeout-ms))
        job (bash-job/start! {:command command
                              :working-dir dir
                              :conversation-id conversation-id
                              :tool-use-id (:tool-use-id ctx)})]
    (if background
      (let [stop (bash-job/progress-loop job emit)]
        (future (deref (:exited job) (* 24 60 60 1000) nil) (stop))
        {:content [(llm/text-block (bash-job/background! job {:emit emit :inject-message inject-message}
                                                         "Started"))]})
      ;; Stream the log tail while we wait, so a slow foreground command still
      ;; shows progress in the UI.
      (let [stop (bash-job/progress-loop job emit)
            {:keys [finished?]} (bash-job/wait! job timeout)]
        (if finished?
          (do (stop) (bash-result job))
          ;; Still running after the timeout: hand it back rather than killing
          ;; work the agent may be waiting on. The progress loop keeps running
          ;; until the job exits.
          (do
            (future (deref (:exited job) (* 24 60 60 1000) nil) (stop))
            {:content [(llm/text-block
                        (bash-job/background! job {:emit emit :inject-message inject-message}
                                              (str "Still running after " timeout "ms, so it is now a")))]}))))))

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

         {:name "read_file"
          :description "Read a text file with line numbers. Paths resolve against the working directory; a path outside it is refused. Use offset and limit for large files."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:path {:type "string" :description "File to read."}
                                      :offset {:type "integer" :description "First line, 1-based."}
                                      :limit {:type "integer" :description "How many lines."}}
                         :required ["path"]}
          :handler files/read-file}

         {:name "write_file"
          :description "Create or overwrite a file, creating parent directories. Use patch for edits to an existing file; use this for new files or complete rewrites."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:path {:type "string"}
                                      :content {:type "string"}
                                      :append {:type "boolean" :description "Append instead of overwriting."}}
                         :required ["path" "content"]}
          :handler files/write-file}

         {:name "list_directory"
          :description "List the immediate children of a directory, directories first, with sizes."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:path {:type "string" :description "Directory, default the working directory."}
                                      :limit {:type "integer"}}
                         :required []}
          :handler files/list-directory}

         {:name "find_files"
          :description "Find files by glob pattern and/or by text in their path or contents. Skips .git, node_modules, target and similar by default."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:dir {:type "string" :description "Where to start; default the working directory."}
                                      :pattern {:type "string" :description "Glob on the path relative to dir, e.g. src/**/*.clj"}
                                      :query {:type "string" :description "Case-insensitive substring of the path, or of file contents when content greps."}
                                      :content {:type "string" :enum ["skip" "path" "content" "only"]
                                                :description "Whether to grep file contents. Default skip."}
                                      :limit {:type "integer"}
                                      :include_hidden {:type "boolean"}}
                         :required []}
          :handler files/find-files}

         {:name "skill"
          :description "Load a skill: a packaged set of instructions for a recurring job. action list shows what is installed; action cat with a name returns its instructions. Load the skill before doing the task it covers, because skills encode how this project does things."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:action {:type "string" :enum ["list" "cat"]}
                                      :name {:type "string" :description "Skill name, for action cat."}}
                         :required []}
          :handler (fn [ctx args] (skills/run-skills ctx args))}

         {:name "compact_in_place"
          :description "Compact your own context when the conversation gets long. action index lists message rows with ids and a gist; action compact takes trim (row ids to shrink to a marker) and collapse ([{from,to,note}] ranges replaced by one summary message); action skip dismisses the nudge."
          :default-on? true
          :input-schema {:type "object"
                         :properties {:action {:type "string" :enum ["index" "compact" "skip"]}
                                      :trim {:type "array" :items {:type "string"}}
                                      :collapse {:type "array"
                                                 :items {:type "object"
                                                         :properties {:from {:type "string"}
                                                                      :to {:type "string"}
                                                                      :note {:type "string"}}
                                                         :required ["from" "to" "note"]}}}
                         :required ["action"]}
          :handler compact/run-compact-in-place}

         {:name "message_user"
          :description "Send the user a message in the chat. Use it to report something mid-turn, ask a question and wait for the answer, react to an earlier message with one emoji, or attach files. Plain text in a normal reply also reaches the user, so reach for this when you need an answer or want the message to stand out."
          :default-on? false
          :input-schema {:type "object"
                         :properties {:text {:type "string"}
                                      :reply_to {:type "integer" :description "Message id to reply to, or to react to."}
                                      :reaction {:type "string" :description "One emoji; needs reply_to."}
                                      :attachments {:type "array" :items {:type "string"} :description "File paths to attach."}
                                      :end_turn {:type "boolean" :description "Whether the turn ends after this message."}
                                      :question {:type "string" :description "Ask the user and wait for the reply."}
                                      :options {:type "array" :items {:type "string"} :description "Suggested answers."}
                                      :timeout_ms {:type "integer" :description "How long to wait for the reply."}}
                         :required []}
          :handler message-user/run-message-user}

         {:name "subagent"
          :description "Spawn a subagent with its own context window to do a self-contained job. Returns only its final message."
          :default-on? false
          :input-schema {:type "object"
                         :properties {:slug {:type "string"}
                                      :prompt {:type "string"}
                                      :toolset {:type "string" :enum ["default" "readonly"]}
                                      :model {:type "string"}}
                         :required ["prompt"]}
          :handler run-subagent}

         (-> (browse/browser-tool-def)
             (assoc :default-on? true))]))

(def ^:private readonly-tools
  #{"bash" "change_dir" "read_image" "read_file" "list_directory" "find_files" "skill"})

(defn- mcp-tools
  "Tools from configured MCP servers. Only cached definitions are returned:
  a server that is down costs the agent a tool, never a stuck turn."
  [working-dir]
  (try
    (mcp/ensure-tools! (str working-dir))
    (catch Throwable _ nil)))

(defn tool-by-name
  "The tool map for `name`, static tools first, then MCP."
  [name]
  (or (get all-tools name)
      (get (into {} (map (juxt :name identity) (mcp-tools (System/getProperty "user.dir")))) name)))

(defn tool-defs
  "API tool definitions for a toolset. :readonly offers only tools that cannot
  write; MCP tools are excluded there because we cannot know what they do."
  [toolset]
  (let [wd (System/getProperty "user.dir")
        static (->> (vals all-tools)
                    (filter :default-on?)
                    (filter #(or (not= :readonly toolset) (readonly-tools (:name %))))
                    (mapv llm/->api-tool))]
    (into static
          (when (not= :readonly toolset)
            (mapv llm/->api-tool (mcp-tools wd))))))

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
        tool (or (get all-tools name)
                 ;; MCP tools are addressed by SERVER.TOOL and resolved at call
                 ;; time, because the cache may have been filled after this
                 ;; conversation started.
                 (when (str/includes? (str name) ".")
                   (some #(when (= (:name %) name) %)
                         (try (mcp/tool-defs (System/getProperty "user.dir"))
                              (catch Throwable _ nil)))))]
    (try
      (if-not tool
        {:type :tool-result :tool-use-id id :is-error true
         :content [(llm/text-block (str "unknown tool: " name))]}
        (let [res (try ((:handler tool) (assoc ctx :tool-use-id id)
                                     (keywordize-args (or input {})))
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
            (:ui res) (assoc :ui (:ui res))
            ;; A tool may hand the loop a directive it must act on rather than
            ;; a message for the model (compact_in_place does this). Passing it
            ;; through keeps `execute` a transport; the loop decides.
            (:directive res) (assoc :directive (:directive res)))))
      (catch Throwable t
        {:type :tool-result :tool-use-id id :is-error true
         :content [(llm/text-block (str "tool error: " (.getMessage t)))]}))))
