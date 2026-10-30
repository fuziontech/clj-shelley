(ns shelley.terminal-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shelley.terminal :as term]))

(defn- collect!
  "Spawn, run a command, wait for output to contain `marker`."
  [id marker]
  (let [out (atom "")
        s (term/spawn {:id id :cwd "/tmp" :on-output (fn [x] (swap! out str x))})]
    (Thread/sleep 800)
    (term/send-input! s "echo MARKER-1\r")
    (let [deadline (+ (System/currentTimeMillis) 8000)]
      (while (and (< (System/currentTimeMillis) deadline)
                  (not (str/includes? @out "MARKER-1")))
        (Thread/sleep 100)))
    s))

(deftest a-pty-is-available
  (is (term/available?) "script(1) is required for any terminal support"))

(deftest durable-sessions-survive-the-client
  (when (term/durable?)
    (testing "output streams to the client"
      (let [s (collect! "term-test-a" "MARKER-1")]
        (is (str/includes? @(:output s) "MARKER-1"))
        (term/close! s)))
    (testing "the shell outlives the client and has scrollback"
      (is (term/durable-exists? (term/session-name "term-test-a")))
      (is (str/includes? (str (term/scrollback (term/session-name "term-test-a") 500))
                         "MARKER-1")))
    (testing "killing it really ends it"
      (term/kill-session! "term-test-a")
      (is (not (term/durable-exists? (term/session-name "term-test-a")))))))

(deftest reattach-keeps-state
  (when (term/durable?)
    (let [id "term-test-b"
          s1 (term/spawn {:id id :cwd "/tmp"})
          _ (Thread/sleep 500)]
      (term/send-input! s1 "export KEPT=yes\r")
      (Thread/sleep 500)
      (term/close! s1)
      ;; A fresh client on the same conversation gets the same shell.
      (let [s2 (term/spawn {:id id :cwd "/tmp"})
            out (atom "")
            _ (Thread/sleep 800)]
        ;; Redirect as a reattaching client would.
        (reset! (:on-output s2) (fn [x] (swap! out str x)))
        (term/send-input! s2 "echo KEPT=$KEPT\r")
        (let [deadline (+ (System/currentTimeMillis) 8000)]
          (while (and (< (System/currentTimeMillis) deadline)
                      (not (str/includes? @out "KEPT=yes")))
            (Thread/sleep 100)))
        (is (str/includes? @out "KEPT=yes"))
        (term/close! s2)
        (term/kill-session! id)))))

(deftest session-names-are-safe
  (testing "ids are sanitised into something tmux accepts"
    (is (= "shelley-abc-123" (term/session-name "abc-123")))
    (is (not (str/includes? (term/session-name "a/b:c d") "/")))
    (is (not (str/includes? (term/session-name "a/b:c d") ":")))))

(deftest describe-reports-capabilities
  (let [{:keys [pty durable]} (term/describe)]
    (is (boolean? pty))
    (is (boolean? durable))))
