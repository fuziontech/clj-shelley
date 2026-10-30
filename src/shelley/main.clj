(ns shelley.main
  "Entry points: `serve` for the web UI, `run` for one-off CLI turns."
  (:require [shelley.server :as server]
            [shelley.db :as db]
            [shelley.loop :as loop]
            [shelley.tools :as tools]
            [shelley.llm :as llm]
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
                 :system [server/system-prompt (str "Working directory: " @cwd)]
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

(defn -main [& args]
  (let [[cmd & rest] args]
    (case cmd
      "serve" (serve (Integer/parseInt (or (first rest) (System/getenv "PORT") "8000")))
      "run" (run-cli (str/join " " rest))
      (do (println "usage: clojure -M:run serve [port] | run \"<prompt>\"")
          (System/exit 1)))))
