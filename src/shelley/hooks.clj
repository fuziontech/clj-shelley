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
  {:ok false :error s}. Never throws."
  [name input]
  (if-not (available? name)
    {:ok true :skipped true}
    (try
      (let [pb (ProcessBuilder. [(.getAbsolutePath (hook-file name))])
            _ (.directory pb (io/file (System/getProperty "user.dir")))
            p (.start pb)]
        (write-input! p input)
        (let [out (future (slurp (.getInputStream p)))
              err (future (slurp (.getErrorStream p)))
              done (.waitFor p *timeout-seconds* TimeUnit/SECONDS)]
          (cond
            (not done) (do (.destroyForcibly p) {:ok false :error (str name " hook timed out")})
            (zero? (.exitValue p)) {:ok true :out @out}
            :else {:ok false
                   :error (str name " hook exited " (.exitValue p) ": " (str/trim (str @err)))})))
      (catch Exception e
        {:ok false :error (str name " hook failed: " (.getMessage e))}))))

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
