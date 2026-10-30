(ns shelley.skills
  "Agent Skills: directories containing a SKILL.md, discovered on disk and
  advertised to the model by name and description.

  This follows the Agent Skills specification (https://agentskills.io): a skill
  is a directory holding SKILL.md whose frontmatter gives a `name` and a
  `description`, followed by markdown the model reads on demand. Only the name
  and description go into the system prompt — the body is loaded when the model
  asks for it. That keeps a large skill library nearly free of context cost,
  which is the whole point of the format.

  Search order, later directories winning on name collisions:
  built-ins, ~/.config/agents/skills, ~/.agents/skills, the project's
  .shelley/skills, SHELLEY_SKILLS_DIRS (colon-separated).
  A skill may be disabled per project with a `disable_skills` setting."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- read-frontmatter
  "Split a SKILL.md into [frontmatter-map body]. Frontmatter is the delimited
  `---` block at the top; the parser handles the flat `key: value` pairs the
  specification requires (name, description, license, ...), not full YAML."
  [text]
  (let [text (str/replace text "\r\n" "\n")]
    (if (str/starts-with? text "---\n")
      (let [end (str/index-of text "\n---" 4)]
        (if end
          (let [raw (subs text 4 end)
                body (str/triml (subs text (+ end 4)))
                m (reduce (fn [acc line]
                            (if-let [[_ k v] (re-matches #"([A-Za-z][A-Za-z0-9_-]*):\s*(.*)" line)]
                              (assoc acc (keyword k)
                                     (-> v str/trim (str/replace #"^[\"']|[\"']$" "")))
                              acc))
                          {}
                          (str/split-lines raw))]
            [m body])
          [{} text]))
      [{} text])))

(defn- skill-from-dir [^java.io.File dir]
  (let [f (io/file dir "SKILL.md")]
    (when (.isFile f)
      (let [[fm body] (read-frontmatter (slurp f))
            name (:name fm)]
        (when (and name (not (str/blank? (str name))))
          {:name (str name)
           :description (str (:description fm ""))
           :license (:license fm)
           :path (.getAbsolutePath f)
           :dir (.getAbsolutePath dir)
           :body body})))))

(defn skills-in
  "Skills that live directly under `dir` — one subdirectory per skill."
  [dir]
  (let [d (io/file dir)]
    (if (.isDirectory d)
      (->> (.listFiles d)
           (filter #(.isDirectory ^java.io.File %))
           (keep skill-from-dir)
           vec)
      [])))

(defn- builtin-dir
  "Built-in skills ship as classpath resources; when running from a source
  checkout they are also reachable as a plain directory, which is what tests
  and the CLI use."
  []
  (or (some #(let [f (io/file % "skills")]
               (when (.isDirectory f) (.getAbsolutePath f)))
            ["resources" "target/classes"])
      "resources/skills"))

(defn search-paths
  "Directories searched for skills, lowest precedence first."
  ([] (search-paths (System/getProperty "user.dir")))
  ([cwd]
   (let [home (System/getProperty "user.home")
         env-dirs (some-> (System/getenv "SHELLEY_SKILLS_DIRS")
                          (str/split #":"))]
     (vec (concat [(builtin-dir)
                   (str home "/.config/agents/skills")
                   (str home "/.agents/skills")
                   (str home "/.config/clj-shelley/skills")]
                  (when cwd [(str cwd "/.shelley/skills")])
                  env-dirs)))))

(defn discover
  "All skills, name-deduplicated with later search paths winning."
  ([] (discover (System/getProperty "user.dir")))
  ([cwd]
   (->> (search-paths cwd)
        (mapcat skills-in)
        (reduce (fn [acc s] (assoc acc (:name s) s)) {})
        vals
        (sort-by :name)
        vec)))

(defn find-skill
  ([name] (find-skill name (System/getProperty "user.dir")))
  ([name cwd]
   (first (filter #(= name (:name %)) (discover cwd)))))

(defn to-prompt-xml
  "The `<available_skills>` block, as the specification renders it."
  [skills]
  (if (empty? skills)
    ""
    (str "<available_skills>\n"
         (str/join "\n" (for [{:keys [name description path]} skills]
                          (str "  <skill>\n"
                               "    <name>" name "</name>\n"
                               "    <description>" description "</description>\n"
                               (when path (str "    <location>" path "</location>\n"))
                               "  </skill>")))
         "\n</available_skills>")))

(defn install-prompt
  "Instructions that tell the model how to actually use a skill it has seen."
  [skills]
  (when (seq skills)
    (str "Skills are instruction sets for specific jobs. When a task matches a "
         "skill's description, read it before acting — read the file at its "
         "<location>, or run `shelley skill cat <name>` — and follow it. Do not "
         "read skills you do not need.")))

(defn enabled-skills
  "Skills minus the ones disabled for this project."
  ([] (enabled-skills #{} (System/getProperty "user.dir")))
  ([disabled] (enabled-skills disabled (System/getProperty "user.dir")))
  ([disabled cwd]
   (remove #(contains? (set disabled) (:name %)) (discover cwd))))

(defn prompt-section
  "The full skills section for a system prompt: the listing plus how to use it."
  ([] (prompt-section #{} (System/getProperty "user.dir")))
  ([disabled] (prompt-section disabled (System/getProperty "user.dir")))
  ([disabled cwd]
   (let [skills (enabled-skills disabled cwd)]
     (when (seq skills)
       (str (install-prompt skills) "\n\n" (to-prompt-xml skills))))))
