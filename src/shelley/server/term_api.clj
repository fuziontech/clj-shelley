(ns shelley.server.term-api
  "Websocket endpoint for the UI's terminal pane.

  One socket, one shell per conversation. When tmux is available the shell is
  durable: closing the tab detaches, reopening reattaches, and the pane replays
  its scrollback first so the terminal looks the way it was left. Without tmux
  the shell dies with the socket, which is the honest fallback.

  Frames in:  {type: \"input\", data} | {type: \"resize\", cols, rows}
  Frames out: {type: \"history\", data} | {type: \"output\", data} | {type: \"exit\"}"
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]
            [shelley.terminal :as term]))

(defn- send! [ch m] (http/send! ch (json/generate-string m)))

(defn- open-session [req ch]
  (let [params (:params req)
        conv-id (or (:id params) (:conversation_id params))
        cwd (or (:cwd params) (System/getProperty "user.dir"))
        session (term/attach! (or conv-id (str "anon-" (java.util.UUID/randomUUID)))
                              cwd
                              {:on-output (fn [s] (send! ch {:type "output" :data s}))
                               :on-exit (fn [code]
                                          (send! ch {:type "exit" :code code})
                                          (http/close ch))})]
    ;; Replay what the pane already showed, so reattaching is not a blank box.
    (when-let [name (:durable-name session)]
      (when-let [history (term/scrollback name)]
        (when-not (clojure.string/blank? history)
          (send! ch {:type "history" :data history}))))
    (swap! term/sessions update (:id session) assoc :ws ch)
    (http/on-close ch (fn [_] (term/close! session)))
    (http/on-receive ch
                     (fn [msg]
                       (try
                         (let [{:keys [type data cols rows]} (json/parse-string msg true)]
                           (case type
                             "input" (term/send-input! session (str data))
                             "resize" (term/resize! session (or cols 80) (or rows 24))
                             nil))
                         (catch Exception _ nil))))
    session))

(defn handler [req]
  (if-not (term/available?)
    {:status 503
     :headers {"content-type" "application/json"}
     :body "{\"error\":\"no pty available on this host\"}"}
    (http/as-channel req {:on-open (fn [ch] (open-session req ch))})))

(defn kill-handler
  "End a conversation's durable terminal for good."
  [{:keys [params]}]
  (term/kill-session! (:id params))
  {:status 200 :headers {"content-type" "application/json"} :body "{\"ok\":true}"})

(def routes
  [["/api/exec-ws" {:get handler}]
   ["/api/conversation/:id/terminal" {:delete kill-handler}]])
