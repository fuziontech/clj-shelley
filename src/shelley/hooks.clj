(ns shelley.hooks
  "User hooks: executables under $HOME/.config/shelley/hooks/<name> that can
  rewrite the system prompt, mutate a new conversation, rewrite a chat message,
  observe the end of a turn, or draw a status bar.

  The contract is stdin/stdout, so hooks can be written in any language:

    name              stdin                    stdout
    system-prompt     prompt text              replacement prompt text
    new-conversation  JSON                     JSON of mutable fields
    chat-message      JSON                     JSON with a `message` field
    end-of-turn       JSON                     ignored
    status-bar        JSON                     JSON {body, bgcolor, color}

  A failing hook aborts the operation it belongs to, because a hook that
  silently stops running is worse than one that stops the turn: it takes away
  the guarantee the hook was installed to provide. `end-of-turn` is the
  exception — nothing is left to abort — so its failures are logged only."
  (:require [cheshire.core :as json]
            [clojure.java.io :refer [writer]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import (java.util.concurrent TimeUnit)))

(def ^:dynamic *timeout-seconds* 30)

(def hooks-dir
  (or (System/getenv "SHELLEY_HOOKS_DIR")
      (str (System/getProperty "user.home") "/.config/shelley/hooks")))

(defn hook-file [name] (io/file hooks-dir (str name)))

(defn available? [name]
  (let [f (hook-file name)]
    (and (.isFile f) (.canExecute f))))

(defn installed-hooks
  "Names of the executables in the hooks directory, in stable order."
  []
  (->> (or (.listFiles (io/file hooks-dir)) [])
       (filter #(and (.isFile ^java.io.File %) (.canExecute ^java.io.File %)))
       (map #(.getName ^java.io.File %))
       sort
       vec))

(defn installed [] (installed-hooks))

(defn- write-input!
  "Feed a hook its stdin and close the stream: hooks that read until EOF block
  forever without the close."
  [^Process p input]
  (let [w (writer (.getOutputStream p))]
    (.write w ^String (str input))
    (.flush w)
    (.close w)))

(defn run-hook
  "Run `name` with `input` on stdin. Returns {:ok true :out s} or
  {:ok false :error s}. Never throws.

  opts: :env (extra environment variables), :cwd, :timeout-seconds.

  Slash-command hooks get their arguments in the environment as well as on
  stdin, because a shell script is much easier to write that way."
  ([name input] (run-hook name input nil))
  ([name input {:keys [env cwd timeout-seconds]}]
   (if-not (available? name)
     {:ok true :skipped true}
     (try
      (let [pb (ProcessBuilder. [(.getAbsolutePath (hook-file name))])
            _ (.directory pb (io/file (or (not-empty (str cwd)) (System/getProperty "user.dir"))))
            _ (when (seq env) (.putAll (.environment pb) ^java.util.Map (into {} env)))
            p (.start pb)
            ;; Capture the pipes before the hook can exit: once the process is
            ;; reaped its streams are closed, and a fast hook hits that race.
            in (.getInputStream p)
            errs (.getErrorStream p)
            out (future (slurp in))
            err (future (slurp errs))]
        (write-input! p input)
        (let [done (.waitFor p (or timeout-seconds *timeout-seconds*) TimeUnit/SECONDS)]
          (cond
            (not done) (do (.destroyForcibly p) {:ok false :error (str name " hook timed out")})
            (zero? (.exitValue p)) {:ok true :out @out}
            :else {:ok false
                   :error (str name " hook exited " (.exitValue p) ": " (str/trim (str @err)))})))
      (catch Exception e
        {:ok false :error (str name " hook failed: " (.getMessage e))})))))

(defn- run-json-hook
  "Round-trip a hook through JSON. Returns the parsed map the hook produced, or
  the input map when the hook is absent."
  [name payload]
  (let [r (run-hook name (json/generate-string payload))]
    (cond
      (not (:ok r)) (if (:skipped r) payload
                        (throw (ex-info (:error r) {:hook name})))
      (str/blank? (:out r)) payload
      :else (try (json/parse-string (:out r) true)
                 (catch Exception e
                   (throw (ex-info (str name " hook returned invalid JSON: " (:out r))
                                   {:hook name} e)))))))

;; ---------------------------------------------------------------------------
;; The five hooks

(defn system-prompt-hook
  "Give the system-prompt hook the rendered prompt and take its replacement.
  An empty replacement is an error: it would leave the agent with no prompt."
  [prompt]
  (if-not (available? "system-prompt")
    prompt
    (let [{:keys [ok out error]} (run-hook "system-prompt" prompt)]
      (cond
        (not ok) (throw (ex-info error {:hook "system-prompt"}))
        (str/blank? out) (throw (ex-info "system-prompt hook returned an empty prompt"
                                        {:hook "system-prompt"}))
        :else out))))

(defn new-conversation-hook
  "Let a hook adjust the fields of a conversation before it is created."
  [payload]
  (run-json-hook "new-conversation" payload))

(defn chat-message-hook
  "Let a hook rewrite an outgoing user message. The hook must return `message`;
  anything else is a contract violation, not a no-op."
  [payload]
  (let [out (run-json-hook "chat-message" payload)]
    (if-not (available? "chat-message")
      out
      (if (str/blank? (str (:message out)))
        (throw (ex-info "chat-message hook returned no message" {:hook "chat-message"}))
        out))))

(defn end-of-turn-hook
  "Observe a finished turn. Failures are logged, never raised."
  [payload]
  (when (available? "end-of-turn")
    (let [{:keys [ok error]} (run-hook "end-of-turn" (json/generate-string payload))]
      (when-not ok (log/warn error)))))

(defn status-bar-hook
  "Render the status bar row for a conversation: {:body, :bgcolor, :color}.
  Returns {:error msg} when the hook fails so the UI can show why its row is
  missing rather than quietly showing nothing."
  [payload]
  (if-not (available? "status-bar")
    nil
    (let [{:keys [ok out error]} (run-hook "status-bar" (json/generate-string payload))]
      (if-not ok
        {:error error}
        (try
          (json/parse-string out true)
          (catch Exception e
            {:error (str "status-bar hook returned invalid JSON: " (.getMessage e))}))))))

;; ---------------------------------------------------------------------------
;; Slash commands
;;
;; A message whose first token is `/something` runs `hooks/slash/something` if
;; such a file exists. The hook either replaces the message with its stdout, or
;; handles it entirely by printing nothing — in which case no turn runs. This is
;; how a user adds a command that never reaches the model at all.

(def ^:private slash-name-pattern #"^[a-zA-Z0-9_][a-zA-Z0-9_-]*$")

(defn parse-slash
  "Split `/cmd some args` into {:command :args}, or nil when the message is not
  a slash command. A message like `/usr/bin/ls` is not a command: the name may
  not contain a slash."
  [message]
  (let [m (str/replace (str message) #"^[ \t\r\n]+" "")]
    (when (str/starts-with? m "/")
      (let [rest (subs m 1)
            [cmd args] (let [i (first (keep-indexed (fn [i c] (when (re-find #"\s" (str c)) i))
                                                   rest))]
                         (if i [(subs rest 0 i) (str/replace (subs rest i) #"^[ \t\r\n]+" "")]
                             [rest ""]))]
        (when (re-matches slash-name-pattern cmd)
          {:command cmd :args args :raw (str message)})))))

(defn slash-commands
  "The slash commands this machine has hooks for."
  []
  (let [dir (io/file hooks-dir "slash")]
    (if (.isDirectory dir)
      (->> (.listFiles dir) (filter #(.isFile ^java.io.File %))
           (map #(.getName ^java.io.File %)) sort vec)
      [])))

(defn slash-command-hook
  "Run the hook for a slash command. Returns:

    nil                             — not a slash command, or no hook for it
    {:handled? true :message text}   — handled; an empty text means the message
                                       is consumed and no turn should run
    {:handled? true :error text}     — the hook exists and failed

  A hook that exists is authoritative: a failure is reported rather than
  silently falling through to the model, because the user asked for a command
  that specifically does not go to the model."
  [{:keys [message conversation-id is-new-conversation cwd model user-email]}]
  (when-let [{:keys [command args raw]} (parse-slash message)]
    (let [name (str "slash/" command)]
      (when (available? name)
        (let [payload {:command command
                       :args args
                       :raw_message raw
                       :conversation_id conversation-id
                       :is_new_conversation (boolean is-new-conversation)
                       :cwd cwd
                       :model model
                       :user_email user-email}
              env {"SHELLEY_SLASH_COMMAND" command
                   "SHELLEY_SLASH_ARGS" (str args)
                   "SHELLEY_CONVERSATION_ID" (str conversation-id)
                   "SHELLEY_CWD" (str cwd)
                   "SHELLEY_MODEL" (str model)
                   "SHELLEY_USER_EMAIL" (str user-email)}
              {:keys [ok out error]} (run-hook name (json/generate-string payload)
                                               {:env env :cwd cwd})]
          (if ok
            {:handled? true :message (str out) :command command}
            {:handled? true :error (str "/" command ": " error)}))))))
