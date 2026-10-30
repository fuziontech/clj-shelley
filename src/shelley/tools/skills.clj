(ns shelley.tools.skills
  "Skills: markdown instruction packs the agent loads on demand.

  A skill is a directory containing SKILL.md whose YAML frontmatter names it
  and says when to use it. Only the name and description sit in the system
  prompt; the body — which may be long — is read only when the agent asks for
  it. That split is the whole point: dozens of skills cost nothing until used.

  Search order, first match wins by name:

    1. built into clj-shelley (classpath skills, so they work from a jar)
    2. <working-dir>/.shelley/skills
    3. <working-dir>/.claude/skills      (shared with other agents)
    4. ~/.clj-shelley/skills
    5. ~/.config/clj-shelley/skills
    6. ~/.agents/skills

  Frontmatter parsing is deliberately small: `key: value` pairs, quoted or
  plain, plus `|` block scalars. We do not pull a YAML library in for six
  fields, and a skill that yaml-libraries would accept but this parser rejects
  fails loudly rather than silently losing its description."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def max-name-length 64)
(def max-description-length 1024)

;; ---------------------------------------------------------------------------
;; Frontmatter

(defn split-frontmatter
  "Split `content` into [yaml body]. Throws ex-info {:kind :bad-skill} when
  the file does not open with a closed frontmatter block."
  [content]
  (let [content (str/replace (str content) "\r\n" "\n")
        trimmed (str/triml content)]
    (when-not (str/starts-with? trimmed "---")
      (throw (ex-info "SKILL.md must start with YAML frontmatter (---)" {:kind :bad-skill})))
    (let [after-first (subs trimmed 3)
          ;; The opening line may have trailing spaces.
          after-first (str/replace-first after-first #"[ \t]*\n" "")
          idx (str/index-of after-first "\n---")]
      (when-not idx
        (throw (ex-info "SKILL.md frontmatter not closed with ---" {:kind :bad-skill})))
      (let [yaml (subs after-first 0 idx)
            body (subs after-first (+ idx 4))]
        [yaml (str/trim body)]))))

(defn- unquote-scalar [s]
  (let [s (str/trim s)]
    (cond
      (and (>= (count s) 2) (str/starts-with? s "\"") (str/ends-with? s "\""))
      (-> (subs s 1 (dec (count s)))
          (str/replace "\\n" "\n")
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))
      (and (>= (count s) 2) (str/starts-with? s "'") (str/ends-with? s "'"))
      (str/replace (subs s 1 (dec (count s))) "''" "'")
      :else s)))

(defn parse-yaml-map
  "Parse the flat YAML mapping used by skill frontmatter.

  Supports `key: value`, quoted values, multi-line `|` and `>` block scalars,
  and skips nested mappings (metadata) since nothing reads them. Returns a map
  of string keys to string values."
  [yaml]
  (let [lines (str/split-lines (str yaml))]
    (loop [lines lines acc {} current nil]
      (if-let [line (first lines)]
        (cond
          ;; A continuation line of a block scalar, or any indented line.
          (and current (re-matches #"\s+.*" line))
          (recur (rest lines) (update acc current str (str/trim line) "\n") current)

          (str/blank? (str/trim line))
          (recur (rest lines) acc current)

          (re-matches #"\s*#.*" line)
          (recur (rest lines) acc current)

          (re-matches #"\s+.*" line) ; nested key under a mapping we ignore
          (recur (rest lines) acc current)

          :else
          (if-let [[_ k v] (re-matches #"([A-Za-z0-9_.-]+)\s*:\s*(.*)$" line)]
            (let [v (str/trim v)]
              (if (contains? #{"|" ">" "|-" ">-"} v)
                (recur (rest lines) (assoc acc k "") k)
                (recur (rest lines) (assoc acc k (unquote-scalar v)) nil)))
            (recur (rest lines) acc current)))
        (into {} (map (fn [[k v]] [(keyword k) (str/trim v)]) acc))))))

(defn validate-name
  "Names are lowercase letters, digits and single hyphens, 1-64 characters —
  the Agent Skills rule, and the reason a name can be a directory name."
  [name]
  (cond
    (str/blank? (str name)) "name is required"
    (> (count (str name)) max-name-length) (str "name must be at most " max-name-length " characters")
    (not= (str name) (str/lower-case (str name))) "name must be lowercase"
    (str/starts-with? (str name) "-") "name cannot start with a hyphen"
    (str/ends-with? (str name) "-") "name cannot end with a hyphen"
    (str/includes? (str name) "--") "name cannot contain consecutive hyphens"
    (not (re-matches #"[a-z0-9-]+" (str name))) "name can only contain letters, digits and hyphens"
    :else nil))

(defn parse-content
  "Parse SKILL.md content into a skill map. Throws ex-info on invalid input."
  [content {:keys [path source origin activate]}]
  (let [[yaml body] (split-frontmatter content)
        attrs (parse-yaml-map yaml)
        name (:name attrs)
        description (:description attrs)]
    (when (str/blank? description)
      (throw (ex-info "skill description is required" {:kind :bad-skill})))
    (when-let [problem (validate-name name)]
      (throw (ex-info (str "invalid skill name: " problem) {:kind :bad-skill})))
    (when (> (count description) max-description-length)
      (throw (ex-info "skill description is too long" {:kind :bad-skill})))
    {:name name
     :description description
     :when (:when attrs)
     :allowed-tools (:allowed-tools attrs)
     :license (:license attrs)
     :body body
     :path path
     :source source
     :origin origin
     :activate (or activate (str "skill cat " name))}))

(defn parse-file [f origin]
  (let [content (slurp (io/file f))
        skill (parse-content content {:path (.getPath (io/file f))
                                      :source (.getPath (io/file f))
                                      :origin origin})]
    ;; The directory name must match the skill name, or discovery would load
    ;; a skill under the wrong name (and the activation command would miss).
    (let [dir-name (.getName (.getParentFile (io/file f)))]
      (when (not= dir-name (:name skill))
        (throw (ex-info (str "skill name " (pr-str (:name skill))
                             " does not match its directory " (pr-str dir-name))
                        {:kind :bad-skill})))
      skill)))

;; ---------------------------------------------------------------------------
;; Discovery

(defn- skill-md
  "The SKILL.md in `dir`, or nil."
  [dir]
  (->> ["SKILL.md" "skill.md"]
       (map #(io/file dir %))
       (filter #(.isFile %))
       first))

(defn discover-dir
  "Every valid skill directly inside `dir`. Invalid skills are skipped: one
  broken skill must not hide the rest."
  [dir]
  (let [d (io/file (str dir))]
    (if-not (.isDirectory d)
      []
      (->> (or (.listFiles d) (make-array java.io.File 0))
           (filter #(.isDirectory %))
           (keep (fn [^java.io.File sub]
                   (when-let [f (skill-md sub)]
                     (try (parse-file f "File")
                          (catch Exception _ nil)))))
           vec))))

(def builtin-names
  "Built-in skills, listed explicitly rather than scanned, so they are found
  inside a jar as well as on the classpath directories."
  ["commit-hygiene" "running-tests"])

(defn- builtin-resource [name]
  (io/resource (str "shelley/tools/builtin_skills/" name "/SKILL.md")))

(defn builtin-skills []
  (vec (for [n builtin-names
             :let [r (builtin-resource n)]
             :when r]
         (parse-content (slurp r) {:origin "Built into clj-shelley"
                                   :source (str "skills/builtin/" n "/SKILL.md")}))))

(defn user-dirs []
  (let [home (System/getProperty "user.home")]
    (->> [(io/file home ".clj-shelley" "skills")
          (io/file home ".config" "clj-shelley" "skills")
          (io/file home ".agents" "skills")]
         (filter #(.isDirectory %))
         (mapv #(.getPath %)))))

(defn project-dirs [working-dir]
  (->> [(io/file (str working-dir) ".shelley" "skills")
        (io/file (str working-dir) ".claude" "skills")]
       (filter #(.isDirectory %))
       (mapv #(.getPath %))))

(defn search-dirs
  "Directories searched for skills, most specific first."
  [working-dir]
  (concat (project-dirs working-dir) (user-dirs)))

(defn all-skills
  "All skills visible to this conversation, deduplicated by name (the first
  directory to define a name wins — project beats user beats builtin)."
  ([] (all-skills (System/getProperty "user.dir")))
  ([working-dir]
   (let [ordered (concat (mapcat discover-dir (search-dirs working-dir))
                         (builtin-skills))]
     (->> ordered
          (reduce (fn [{:keys [seen out] :as acc} skill]
                    (if (contains? seen (:name skill))
                      acc
                      {:seen (conj seen (:name skill)) :out (conj out skill)}))
                  {:seen #{} :out []})
          :out
          vec))))

(defn find-skill [working-dir name]
  (->> (all-skills working-dir)
       (filter #(= (str name) (:name %)))
       first))

(defn cat-skill
  "The full text the agent sees when it activates a skill. Header first, so a
  skill loaded from a surprising directory is visible as such."
  [skill]
  (str "# Skill: " (:name skill) "\n"
       (when (:origin skill) (str "Source: " (or (:source skill) (:origin skill))
                                 " (" (:origin skill) ")\n"))
       (when (:when skill) (str "When: " (:when skill) "\n"))
       (when (:allowed-tools skill) (str "Allowed tools: " (:allowed-tools skill) "\n"))
       "\n"
       (:body skill)))

(defn- escape-xml [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn to-prompt-xml
  "The <available_skills> block for the system prompt. Mirrors the original's
  format so a prompt written for the Go Shelley still reads correctly."
  [skills]
  (if (empty? skills)
    ""
    (str "<available_skills>\n"
         (str/join
          "\n"
          (for [{:keys [name description activate]} skills]
            (str "<skill>\n"
                 "<name>" (escape-xml name) "</name>\n"
                 "<description>" (escape-xml description) "</description>\n"
                 "<activate>" (escape-xml activate) "</activate>\n"
                 "</skill>")))
         "\n</available_skills>")))

;; ---------------------------------------------------------------------------
;; Tool handler

(defn run-skills
  "The `skill` tool: list what is available, or load one by name (the
  equivalent of `skill cat <name>` on the command line)."
  [ctx {:keys [action name] :as _args}]
  (let [wd (or (some-> (:working-dir ctx) deref) (System/getProperty "user.dir"))
        skills (all-skills wd)]
    (case (or action (if name "cat" "list"))
      "list"
      (if (empty? skills)
        {:content [{:type :text :text "No skills are installed. Add one at .shelley/skills/<name>/SKILL.md or ~/.clj-shelley/skills/<name>/SKILL.md."}]}
        {:content [{:type :text
                    :text (str "Available skills (load one with this tool, action \"cat\"):\n\n"
                               (str/join "\n\n" (for [{:keys [name description origin activate]} skills]
                                                   (str "- " name ": " description
                                                        "\n  activate: " activate
                                                        "\n  from: " (or origin "file"))))) }]})

      "cat"
      (if-let [skill (find-skill wd name)]
        {:content [{:type :text :text (cat-skill skill)}]}
        {:is-error true
         :content [{:type :text
                    :text (str "No skill named " (pr-str name) ". Available: "
                               (str/join ", " (map :name skills)))}]})

      {:is-error true
       :content [{:type :text :text (str "unknown skill action " (pr-str action) "; use \"list\" or \"cat\"")}]})))
