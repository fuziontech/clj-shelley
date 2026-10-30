(ns shelley.main
  "Entry points: `serve` for the web UI, `run` for one-off CLI turns."
  (:require [shelley.cli :as cli]
            [shelley.server :as server]
            [shelley.db :as db]
            [shelley.loop :as loop]
            [shelley.tools :as tools]
            [shelley.llm :as llm]
            [shelley.skills :as skills]
            [clojure.string :as str]))

(defn- print-event [e]
  (case (:type e)
    :text-delta (print (:text e))
    :thinking-delta nil
    :tool-start (println (str "\n\n→ " (:name e) " " (pr-str (:input e))))
    :tool-end (println (str "← " (if (:is-error e) "ERROR" "ok")
                            " (" (:elapsed-ms e) "ms) "
                            (subs (str/join "\n" (:content e)) 0
                                  (min 400 (count (str/join "\n" (:content e)))))))
    :warning (println (str "\n! " (:text e)))
    :error (println (str "\n!! " (:text e)))
    nil)
  (flush))

(defn run-cli
  "Run a single turn in the terminal, streaming output as it arrives."
  [prompt]
  (db/init!)
  (let [conv (db/create-conversation! {:title (subs prompt 0 (min 80 (count prompt)))
                                       :model server/default-model})
        cwd (atom (System/getProperty "user.dir"))
        ctx {:working-dir cwd
             :conversation-id (:id conv)
             :emit print-event
             :base-url (llm/base-url)}
        result (loop/run-turn
                {:model server/default-model
                 :system (cond-> [server/system-prompt (str "Working directory: " @cwd)]
                           (skills/prompt-section) (conj (skills/prompt-section)))
                 :messages [(llm/user-message prompt)]
                 :tools (tools/tool-defs nil)
                 :ctx ctx
                 :emit print-event
                 :record! (fn [m] (db/append-message! {:conversation-id (:id conv)
                                                       :role (:role m)
                                                       :content (:content m)}))})]
    (println)
    (println (str "[" (:status result) " · conversation " (:id conv) "]"))
    (shutdown-agents)
    (System/exit (if (= :complete (:status result)) 0 1))))

(defn serve [port]
  (println (str "clj-shelley listening on http://localhost:" port
                "  (model: " server/default-model ")"))
  (server/start! port)
  @(promise))

(defn- usage []
  (println (str/join "\n"
                     ["usage: clojure -M:run <command>"
                      ""
                      "  serve [port]        run the server and web UI"
                      "  run \"<prompt>\"      one-off turn against the local agent (no server)"
                      "  version             server build info"
                      "  ls                  list conversations on a running server"
                      "  show <id>           print a conversation transcript"
                      "  chat <id> \"<msg>\"  send a message and stream the reply"
                      "  new \"<msg>\"         start a conversation and stream it"
                      "  cancel <id>         stop a running turn"
                      "  archive <id> [off]  archive or unarchive"
                      "  skill list          list discoverable skills"
                      "  skill cat <name>    print a skill's instructions"
                      ""])))

(defn -main [& args]
  (let [[cmd & rest] args]
    (case cmd
      "serve" (serve (Integer/parseInt (or (first rest) (System/getenv "PORT") "8000")))
      "run" (run-cli (str/join " " rest))
      "version" (do (cli/version) (shutdown-agents))
      "ls" (do (cli/conversations) (shutdown-agents))
      "show" (do (cli/show (first rest)) (shutdown-agents))
      "chat" (do (cli/chat (first rest) (str/join " " (rest rest)))
                 (shutdown-agents) (System/exit 0))
      "new" (do (cli/new-conversation (str/join " " rest) {})
                (shutdown-agents) (System/exit 0))
      "cancel" (do (cli/cancel (first rest)) (shutdown-agents))
      "archive" (do (cli/archive (first rest) (not= "off" (second rest))) (shutdown-agents))
      "skill" (case (first rest)
                "cat" (if-let [s (skills/find-skill (second rest))]
                        (do (print (:body s)) (flush))
                        (do (println "no such skill:" (second rest)) (System/exit 1)))
                ;; default: list
                (doseq [{:keys [name description]} (skills/discover)]
                  (println (format "%-28s %s" name description))))
      (do (usage) (System/exit 1)))))
