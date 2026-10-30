(ns shelley.gitstate-test
  (:refer-clojure :exclude [root])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shelley.gitstate :as gs]))

(defn- sh [dir & args]
  (:out (let [p (.start (doto (ProcessBuilder. (into ["git"] args))
                          (.directory (io/file dir))
                          (.redirectErrorStream true)))]
          {:out (str/trim (slurp (.getInputStream p))) })))

(defn- temp-repo []
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "gs" (make-array java.nio.file.attribute.FileAttribute 0)))
        path (.getAbsolutePath d)]
    (sh path "init" "-q")
    (sh path "config" "user.email" "t@example.com")
    (sh path "config" "user.name" "T")
    (spit (io/file path "a.txt") "hello\n")
    (sh path "add" "a.txt")
    (sh path "commit" "-qm" "first commit")
    path))

(deftest non-repository-is-a-normal-answer
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "gs2" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (is (nil? (gs/root (.getAbsolutePath d))))
    (let [s (gs/state (.getAbsolutePath d))]
      (is (nil? (:git_repo_root s)))
      (is (= 0 (:git_dirty s))))))

(deftest reads-a-real-repository
  (let [repo (temp-repo)]
    (testing "root, commit, subject and branch"
      (let [s (gs/state repo)]
        (is (= repo (:git_repo_root s)))
        (is (re-seq #"^[0-9a-f]{7,}$" (str (:git_commit s))))
        (is (= "first commit" (:git_subject s)))
        (is (= 0 (:git_dirty s)))))
    (testing "an uncommitted file shows as dirty"
      (spit (io/file repo "b.txt") "new\n")
      (gs/forget! repo)
      (is (= 1 (:git_dirty (gs/state repo)))))))

(deftest cache-can-be-invalidated
  (let [repo (temp-repo)]
    (is (= 0 (:git_dirty (gs/state repo))))
    (spit (io/file repo "c.txt") "x\n")
    (testing "cached until forgotten"
      (is (= 0 (:git_dirty (gs/state repo)))))
    (gs/forget! repo)
    (is (= 1 (:git_dirty (gs/state repo))))))
