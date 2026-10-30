(ns shelley.cli
  "Command-line client for a running clj-shelley server.

  The original ships a Go client in `client/` for exactly this: driving an agent
  that lives in a long-running server, from a terminal. Same idea, same verbs,
  plain java.net.http underneath so there is no dependency to install."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:dynamic *server*
  (or (System/getenv "CLJ_SHELLEY_URL") "http://localhost:8000"))

(defn- http [method path body]
  (let [client (java.net.http.HttpClient/newHttpClient)
        url (str *server* path)
        builder (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                    (.timeout (java.time.Duration/ofSeconds 30))
                    (.header "content-type" "application/json"))
        builder (case method
                  :get (.GET builder)
                  :post (.POST builder (java.net.http.HttpRequest$BodyPublishers/ofString
                                        (json/generate-string (or body {}))))
                  :delete (.DELETE builder))
        resp (.send client (.build builder)
                    (java.net.http.HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (when (>= status 400)
      (throw (ex-info (str "HTTP " status ": " (.body resp)) {:status status})))
    (when-not (str/blank? (.body resp))
      (json/parse-string (.body resp) true))))

(defn version [& _]
  (let [v (http :get "/version" nil)]
    (println (str "clj-shelley " (or (:tag v) "dev")
                  (when-let [c (:commit v)] (str " (" c ")"))))
    (when-let [caps (:capabilities v)] (println "capabilities:" (str/join ", " caps)))))

(defn conversations [& _]
  (let [{:keys [conversations]} (http :get "/api/conversations" nil)]
    (doseq [c conversations]
      (println (format "%s  %-8s %s"
                       (subs (:conversation_id c) 0 8)
                       (if (:agent_working c) "working" "")
                       (or (:title c) (:preview c) ""))))))

(defn- print-event [e]
  (case (:type e)
    :text-delta (print (:text e))
    :tool-start (println (str "\n\n→ " (:name e) " " (pr-str (:input e))))
    :tool-end (println (str "← " (if (:is-error e) "ERROR" "ok")
                            " " (subs (str/join "\n" (:content e)) 0
                                      (min 300 (count (str/join "\n" (:content e)))))))
    :warning (println (str "\n! " (:text e)))
    :error (println (str "\n!! " (:text e)))
    nil)
  (flush))

(defn- stream-events
  "Consume an SSE stream, calling `f` for each JSON event. Returns when `until?`
  says the turn is over."
  [path f until?]
  (let [client (java.net.http.HttpClient/newHttpClient)
        req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str *server* path)))
                (.header "accept" "text/event-stream")
                (.GET)
                (.build))
        resp (.send client req (java.net.http.HttpResponse$BodyHandlers/ofInputStream))
        rdr (java.io.BufferedReader.
             (java.io.InputStreamReader. (.body resp) java.nio.charset.StandardCharsets/UTF_8))
        sb (StringBuilder.)]
    (loop []
      (when-let [line (.readLine rdr)]
        (cond
          (empty? line) (when (pos? (.length sb))
                          (let [payload (str sb)
                                e (try (json/parse-string payload true) (catch Exception _ nil))]
                            (.setLength sb 0)
                            (when e (f e))
                            (when-not (until? e) (recur))))
          (str/starts-with? line "data:") (do (.append sb (subs line 5)) (recur))
          :else (recur))))))

(defn chat
  "Send a message and stream the reply to stdout."
  [id message]
  (let [done (promise)]
    ;; Subscribe before sending, so no event is missed.
    (future
      (stream-events (str "/api/conversation/" id "/stream")
                     (fn [e] (print-event e) (when (= :turn-end (:type e)) (deliver done true)))
                     #(or (= :turn-end (:type %)) (deliver done true))))
    (http :post (str "/api/conversation/" id "/chat") {:message message})
    (deref done 900000 nil)
    (println)))

(defn new-conversation
  "Create a conversation and stream its first turn."
  [message opts]
  (let [{:keys [conversation]} (http :post "/api/conversations/new"
                                     (merge {:message message} opts))
        id (:conversation_id conversation)]
    (println (str "conversation " id))
    (let [done (promise)]
      (stream-events (str "/api/conversation/" id "/stream")
                     (fn [e] (print-event e) (when (= :turn-end (:type e)) (deliver done true)))
                     #(or (= :turn-end (:type %)) false))
      (deref done 900000 nil))
    (println)
    id))

(defn show [id]
  (let [{:keys [messages]} (http :get (str "/api/conversation/" id) nil)]
    (doseq [m messages]
      (let [data (if (string? (:llm_data m))
                   (json/parse-string (:llm_data m) true)
                   (:llm_data m))]
        (println (str "[" (:type m) "]"))
        (doseq [b (:content data)]
          (case (:type b)
            :text (println (:text b))
            :tool-use (println (str "  → " (:name b) " " (pr-str (:input b))))
            :tool-result (println (str "  ← " (str/join "\n" (keep :text (:content b)))))
            nil)))
      (println))))

(defn cancel [id] (http :post (str "/api/conversation/" id "/cancel") {}) (println "cancelling"))

(defn archive [id archived?]
  (http :post (str "/api/conversation/" id "/archive") {:archived (boolean archived?)})
  (println (if archived? "archived" "unarchived")))

;; ---------------------------------------------------------------------------
;; Models
;;
;; Listing models is a question for the gateway, not for our server: the server
;; may not be running, and the answer comes from wherever the LLM lives.

(defn models
  "Print the model ids the configured endpoint offers."
  [& _]
  (let [base (or (System/getenv "LLM_BASE_URL") "https://llm-exe-proxy.int.exe.xyz")
        url (str (str/replace base #"/+$" "") "/models.json")]
    (try
      (let [client (java.net.http.HttpClient/newHttpClient)
            req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                    (.timeout (java.time.Duration/ofSeconds 20))
                    (.GET) (.build))
            resp (.send client req (java.net.http.HttpResponse$BodyHandlers/ofString))
            data (json/parse-string (.body resp) true)
            ids (or (:data data) (:models data) [])
            names (sort (keep #(or (:id %) (:name %) (when (string? %) %)) ids))]
        (if (seq names)
          (doseq [n names] (println n))
          (println "no models listed by" url)))
      (catch Exception e
        (println "could not reach" url "-" (.getMessage e))))))
