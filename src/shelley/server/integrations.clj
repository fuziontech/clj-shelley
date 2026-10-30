(ns shelley.server.integrations
  "Attached integrations: what this VM can talk to, and a way to test it.

  The list comes from the reflection service on the box, which knows which
  credentials are injected here. It is fetched on demand with a short timeout
  and never cached across requests: an integration attached a minute ago must
  appear, and a machine with no reflection service must still render an empty
  list instead of failing."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [shelley.db :as db]
            [shelley.server.http :as http])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers HttpClient$Redirect HttpRequest$BodyPublishers)
           (java.time Duration)))

(def box-host
  (or (System/getenv "CLJ_SHELLEY_BOX_HOST") "exe.xyz"))

(def reflection-url
  (or (System/getenv "CLJ_SHELLEY_REFLECTION_URL")
      (str "https://reflection.int." box-host)))

(defn integration-url
  "Personal integrations live at `<name>.int.<box>`, shared ones at
  `<name>.team.<box>`."
  [name & {:keys [team]}]
  (str "https://" name "." (if team "team" "int") "." box-host))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 3))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn- http-json
  "POST or GET JSON. Returns `{:status :body}` or `{:error msg}`; it never
  throws, because a caller rendering a page should not have to guard."
  [method url body timeout-seconds]
  (try
    (let [req (cond-> (-> (HttpRequest/newBuilder (URI/create url))
                          (.timeout (Duration/ofSeconds timeout-seconds))
                          (.header "content-type" "application/json")
                          (.header "accept" "application/json"))
                body (.method method (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                (nil? body) (.GET))]
      (let [resp (.send @client (.build req) (HttpResponse$BodyHandlers/ofString))]
        {:status (.statusCode resp)
         :body (try (json/parse-string (.body resp) true) (catch Exception _ (.body resp)))}))
    (catch Exception e
      {:error (.getMessage e)})))

(defn fetch-integrations []
  (let [res (http-json "GET" (str reflection-url "/integrations") nil 3)]
    (cond
      (:error res) {:available false :error (:error res) :integrations []}
      (not= 200 (:status res)) {:available false
                                :error (str "reflection returned " (:status res))
                                :integrations []}
      :else {:available true
             :integrations (vec (or (:integrations (:body res)) []))
             :base_url reflection-url})))

(defn- channel->api [c]
  (http/compact-map
   {"channel_id" (:channel-id c)
    "channel_type" (:channel-type c)
    "display_name" (:display-name c)
    "enabled" (boolean (:enabled c))
    "config" (:config c)
    "created_at" (http/iso-time (:created_at c))}))

(defn handler-list [_]
  (let [remote (fetch-integrations)]
    (http/ok {:available (:available remote)
              :error (:error remote)
              :integrations (:integrations remote)
              :source (:base_url remote)
              :channels (mapv channel->api (db/list-notification-channels))
              :kinds [{:kind "slack" :name "Slack" :description "Post a message to a Slack hook"}
                      {:kind "notify" :name "Notify" :description "Send a desktop notification"}]})))

(defn- attached? [integrations name]
  (some (fn [i]
          (let [n (or (:name i) (:id i) (:type i))]
            (when (= (str n) (str name)) i)))
        integrations))

(defn handler-slack-test
  "POST /api/integrations/slack/test — `{name, team, message}`.

  Refuses a name that reflection does not report: sending a message to an
  integration nobody attached would fail at the network anyway, and a clear
  error is more useful than a timeout."
  [req]
  (let [body (http/parse-body req)
        name (or (:name body) "slack-hook")
        message (or (:message body) (:text body) "Hello from Shelley")
        team (http/truthy? (:team body))
        remote (fetch-integrations)
        found (or (attached? (:integrations remote) name)
                  (attached? (:integrations remote) "slack"))]
    (cond
      (not (:available remote))
      (http/unavailable (str "integrations are not reachable: " (:error remote)))

      (nil? found)
      (http/not-found (str "no attached integration named " name))

      :else
      (let [url (or (:webhook_url body) (:url found) (integration-url name :team team))
            res (http-json "POST" url {:text message} 8)]
        (if (:error res)
          (http/error-response 502 (str "could not post to " url ": " (:error res))
                               {"url" url})
          (http/ok {"ok" (<= 200 (:status res) 299)
                    "status" (:status res)
                    "url" url
                    "integration" name}))))))

(defn handler-notify-test
  "POST /api/integrations/notify/test — `{title, body}`."
  [req]
  (let [body (http/parse-body req)
        title (or (:title body) "Shelley")
        text (or (:body body) (:message body) "Test notification")
        url (or (:url body) (integration-url "notify"))
        res (http-json "POST" url {:title title :body text} 8)]
    (if (:error res)
      (http/error-response 502 (str "could not reach " url ": " (:error res)) {"url" url})
      (http/ok {"ok" (<= 200 (:status res) 299) "status" (:status res) "url" url}))))

(defn handler-channel-save
  "POST /api/integrations/channels — remember a webhook so it survives restarts."
  [req]
  (let [body (http/parse-body req)
        name (or (:name body) (:channel_type body))]
    (if (str/blank? (str name))
      (http/bad-request "name is required")
      (http/ok (channel->api
                (db/save-notification-channel!
                 {:channel-id (or (:channel_id body) (:id body))
                  :channel-type (or (:channel_type body) name)
                  :display-name (or (:display_name body) name)
                  :enabled (if (contains? body :enabled) (http/truthy? (:enabled body)) true)
                  :config (or (:config body) (select-keys body [:url :team :channel]))}))))))

(defn handler-channel-delete [req]
  (let [id (http/path-param req :id)]
    (if (zero? (db/delete-notification-channel! id))
      (http/not-found "channel not found")
      (http/ok {"status" "deleted" "id" id}))))

(def routes
  [["/api/integrations" {:get handler-list}]
   ["/api/integrations/slack/test" {:post handler-slack-test}]
   ["/api/integrations/notify/test" {:post handler-notify-test}]
   ["/api/integrations/channels" {:get (fn [_]
                                        (http/ok {"channels" (mapv channel->api
                                                                   (db/list-notification-channels))}))
                                  :post handler-channel-save}]
   ["/api/integrations/channels/:id" {:delete handler-channel-delete}]])
