(ns shelley.skills-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [shelley.skills :as skills]))

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "skills" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-skill! [root name fm body]
  (let [d (io/file root name)]
    (.mkdirs d)
    (spit (io/file d "SKILL.md") (str "---\n" fm "\n---\n\n" body))))

(deftest frontmatter-parsing
  (testing "name and description are read, body keeps its markdown"
    (let [dir (tmp-dir)]
      (write-skill! dir "demo" "name: demo\ndescription: Does a demo thing"
                    "# Demo\n\nDo it.")
      (let [[s] (skills/skills-in dir)]
        (is (= "demo" (:name s)))
        (is (= "Does a demo thing" (:description s)))
        (is (= "# Demo\n\nDo it." (:body s))))))
  (testing "quoted values are unquoted"
    (let [dir (tmp-dir)]
      (write-skill! dir "q" "name: q\ndescription: \"Quoted: yes\"" "body")
      (is (= "Quoted: yes" (:description (first (skills/skills-in dir)))))))
  (testing "a directory without SKILL.md is not a skill"
    (let [dir (tmp-dir)]
      (.mkdirs (io/file dir "empty"))
      (is (empty? (skills/skills-in dir)))))
  (testing "a SKILL.md without a name is skipped"
    (let [dir (tmp-dir)]
      (write-skill! dir "nameless" "description: nothing" "body")
      (is (empty? (skills/skills-in dir))))))

(deftest discovery-precedence
  (testing "later search paths win on name collisions"
    (let [a (tmp-dir) b (tmp-dir)]
      (write-skill! a "dup" "name: dup\ndescription: from a" "a-body")
      (write-skill! b "dup" "name: dup\ndescription: from b" "b-body")
      (let [found (->> [a b]
                       (mapcat skills/skills-in)
                       (reduce (fn [acc s] (assoc acc (:name s) s)) {}))]
        (is (= "from b" (:description (get found "dup"))))))))

(deftest discovery-from-disk
  (testing "the vendored built-in skills are discoverable"
    (let [names (set (map :name (skills/discover)))]
      (is (contains? names "schedule"))
      (is (contains? names "excalidraw"))))
  (testing "a project-local skill directory is searched"
    (let [proj (tmp-dir)]
      (write-skill! (io/file proj ".shelley" "skills") "local"
                    "name: local\ndescription: project skill" "body")
      (is (some #(= "local" (:name %)) (skills/discover (.getAbsolutePath proj)))))))

(deftest prompt-section
  (testing "the listing carries name, description and location but not the body"
    (let [xml (skills/to-prompt-xml [{:name "x" :description "desc" :path "/p/SKILL.md" :body "SECRET BODY"}])]
      (is (re-find #"<name>x</name>" xml))
      (is (re-find #"<description>desc</description>" xml))
      (is (not (re-find #"SECRET BODY" xml)))))
  (testing "disabled skills are left out"
    (let [section (skills/prompt-section #{"schedule"})]
      (is (not (re-find #"<name>schedule</name>" section)))
      (is (re-find #"<name>excalidraw</name>" section))))
  (testing "no skills means no section at all"
    (is (nil? (skills/prompt-section (set (map :name (skills/discover))) (System/getProperty "user.dir"))))))

(deftest lookup
  (is (= "schedule" (:name (skills/find-skill "schedule"))))
  (is (nil? (skills/find-skill "no-such-skill")))
  (is (re-find #"Scheduled" (:body (skills/find-skill "schedule")))))
