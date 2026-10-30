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
    (is (string? (:error (hooks/status-bar-hook {}))))))

(deftest timeout-kills-the-hook
  (install! "end-of-turn" "sleep 10")
  (with-redefs [hooks/*timeout-seconds* 1]
    (let [start (System/currentTimeMillis)]
      (hooks/end-of-turn-hook {})
      (is (< (- (System/currentTimeMillis) start) 8000)))))

;; ---------------------------------------------------------------------------
;; Slash commands
;;
;; A slash command is a hook the user invokes by typing it. The message never
;; reaches the model unless the hook prints a replacement, so the parsing rules
;; and the "hook exists means it owns the message" rule are the contract.

(defn- install-slash! [command script]
  (let [d (io/file dir "slash")]
    (.mkdirs d)
    (let [f (io/file d command)]
      (spit f (str "#!/bin/bash\n" script "\n"))
      (.setExecutable f true)
      f)))

(deftest parse-slash-rules
  (is (= {:command "deploy" :args "now please" :raw "/deploy now please"}
         (hooks/parse-slash "/deploy now please")))
  (is (= "deploy" (:command (hooks/parse-slash "/deploy"))))
  (is (= "" (:args (hooks/parse-slash "/deploy"))))
  (testing "leading whitespace is tolerated, as upstream does"
    (is (= "x" (:command (hooks/parse-slash "   /x")))))
  (testing "not a command"
    (is (nil? (hooks/parse-slash "hello")))
    (is (nil? (hooks/parse-slash "/usr/bin/ls")))
    (is (nil? (hooks/parse-slash "/")))))

(deftest slash-without-a-hook-is-not-handled
  (is (nil? (hooks/slash-command-hook {:message "/nosuchcommand"}))))

(deftest a-slash-hook-owns-its-message
  (install-slash! "deploy" "echo \"deploying $SHELLEY_SLASH_ARGS\"")
  (testing "stdout replaces the message"
    (let [r (hooks/slash-command-hook {:message "/deploy prod"})]
      (is (:handled? r))
      (is (= "deploying prod\n" (:message r)))
      (is (= "deploy" (:command r)))))
  (testing "silence consumes the message: no turn runs"
    (install-slash! "deploy" "true")
    (let [r (hooks/slash-command-hook {:message "/deploy prod"})]
      (is (:handled? r))
      (is (str/blank? (:message r)))))
  (testing "a failing command is an error, not a fall-through to the model"
    (install-slash! "deploy" "exit 3")
    (let [r (hooks/slash-command-hook {:message "/deploy"})]
      (is (:handled? r))
      (is (string? (:error r))))))

(deftest slash-command-gets-its-arguments
  (install-slash! "args" "echo \"$SHELLEY_SLASH_COMMAND|$SHELLEY_SLASH_ARGS\"")
  (testing "stdout is the message verbatim, trailing newline and all, as upstream does"
    (is (= "args|one two  three\n"
           (:message (hooks/slash-command-hook {:message "/args one two  three"}))))
    (testing "arguments keep their internal spacing"
      (is (str/starts-with? (:message (hooks/slash-command-hook {:message "/args a  b"}))
                            "args|a  b")))))
