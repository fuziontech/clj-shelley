(ns shelley.server.term-api
  "Websocket endpoint for the UI's terminal pane.

  One socket, one shell. Input frames are written to the pty, output frames are
  pushed back as they arrive, and closing the socket kills the shell."
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]
            [shelley.terminal :as term]))

(defn handler [req]
  (if-not (term/available?)
    {:status 503 :headers {"content-type" "application/json"}
     :body "{\"error\":\"no pty available on this host\"}"}
    (http/as-channel req
      {:on-open (fn [ch]
                  (let [cwd (get-in req [:params :cwd])
                        session (atom nil)]
                    (reset! session
                            (term/spawn {:cwd (or cwd (System/getProperty "user.dir"))
                                         :on-output (fn [s]
                                                      (http/send! ch (json/generate-string
                                                                      {:type "output" :data s})))
                                         :on-exit (fn [code]
                                                    (http/send! ch (json/generate-string
                                                                    {:type "exit" :code code}))
                                                    (http/close ch))}))
                    (swap! term/sessions update (:id @session) assoc :ws ch)
                    (http/on-close ch (fn [_] (term/close! @session)))
                    (http/on-receive ch
                                     (fn [msg]
                                       (try
                                         (let [{:keys [type data cols rows]} (json/parse-string msg true)]
                                           (case type
                                             "input" (term/send-input! @session (str data))
                                             "resize" (term/resize! @session (or cols 80) (or rows 24))
                                             nil))
                                         (catch Exception _ nil))))))})))

(def routes
  [["/api/exec-ws" {:get handler}]])
