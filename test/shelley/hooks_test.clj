(ns shelley.hooks-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [shelley.hooks :as hooks]))

(def dir (.toFile (java.nio.file.Files/createTempDirectory
                   "hooks" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- install!
  "Write an executable hook script."
  [name script]
  (let [f (io/file dir name)]
    (spit f (str "#!/bin/bash\n" script "\n"))
    (.setExecutable f true)
    f))

(use-fixtures :each
  (fn [t]
    (with-redefs [hooks/hooks-dir (.getAbsolutePath dir)]
      (doseq [f (.listFiles dir)] (.delete f))
      (t))))

(deftest absent-hooks-are-passthroughs
  (testing "no hook installed means the value comes back unchanged"
    (is (= "the prompt" (hooks/system-prompt-hook "the prompt")))
    (is (= {:message "hi"} (hooks/chat-message-hook {:message "hi"})))
    (is (nil? (hooks/status-bar-hook {})))))

(deftest system-prompt-hook-rewrites
  (install! "system-prompt" "cat; echo 'APPENDED'")
  (let [out (hooks/system-prompt-hook "base")]
    (is (str/includes? out "base"))
    (is (str/includes? out "APPENDED"))))

(deftest system-prompt-hook-refuses-empty
  (install! "system-prompt" "cat > /dev/null")
  (is (thrown? Exception (hooks/system-prompt-hook "base"))))

(deftest failing-hook-aborts
  (testing "a non-zero exit raises rather than silently continuing"
    (install! "chat-message" "echo 'deliberate failure' >&2; exit 3")
    (let [e (try (hooks/chat-message-hook {:message "x"}) nil
                 (catch Exception e e))]
      (is (some? e))
      (is (str/includes? (.getMessage e) "deliberate failure")))))

(deftest chat-message-hook-rewrites
  (install! "chat-message" "jq -c '.message = \"rewritten\"'")
  (is (= "rewritten" (:message (hooks/chat-message-hook {:message "original"})))))

(deftest chat-message-hook-requires-message
  (install! "chat-message" "echo '{}'")
  (is (thrown? Exception (hooks/chat-message-hook {:message "original"}))))

(deftest new-conversation-hook-merges
  (install! "new-conversation" "jq -c '.model = \"test/model\"'")
  (is (= "test/model" (:model (hooks/new-conversation-hook {:prompt "p" :model "x"})))))

(deftest end-of-turn-hook-does-not-throw
  (install! "end-of-turn" "exit 1")
  (is (nil? (hooks/end-of-turn-hook {:conversation_id "c"}))))

(deftest status-bar-hook
  (testing "returns whatever the hook prints"
    (install! "status-bar" "echo '{\"body\":\"2 files changed\",\"bgcolor\":\"#333\"}'")
    (is (= "2 files changed" (:body (hooks/status-bar-hook {})))))
  (testing "invalid JSON is reported, not swallowed"
    (install! "status-bar" "echo 'not json'")
    (is (contains? (:error (hooks/status-bar-hook {})) :error))))

(deftest timeout-kills-the-hook
  (install! "end-of-turn" "sleep 10")
  (with-redefs [hooks/*timeout-seconds* 1]
    (let [start (System/currentTimeMillis)]
      (hooks/end-of-turn-hook {})
      (is (< (- (System/currentTimeMillis) start) 8000)))))
