(ns shelley.channels
  "Outbound notifications and inbound chat channels.

  The original can surface a turn into Slack and take messages back from it.
  This implements the parts that work without an OAuth dance:

  * **outbound** — post turn summaries to a Slack incoming webhook, or send a
    push notification through the exe.dev notify integration;
  * **inbound** — a Slack Events API endpoint with request-signature
    verification, mapping a Slack message to a conversation turn.

  Configuration comes from the environment so no secrets live in the database:
  SLACK_WEBHOOK_URL, SLACK_SIGNING_SECRET, NOTIFY_URL."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn slack-webhook [] (System/getenv "SLACK_WEBHOOK_URL"))
(defn slack-secret [] (System/getenv "SLACK_SIGNING_SECRET"))
(defn notify-url [] (or (System/getenv "NOTIFY_URL") "https://notify.int.exe.xyz/"))

(defn- post-json [url body]
  (let [client (HttpClient/newHttpClient)
        req (-> (HttpRequest/newBuilder (URI/create url))
                (.timeout (Duration/ofSeconds 10))
                (.header "content-type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                (.build))]
    (.send client req (HttpResponse$BodyHandlers/ofString))))

(defn slack-post!
  "Post text to the configured Slack webhook. Returns {:ok bool :status n}."
  [text]
  (if-not (slack-webhook)
    {:ok false :error "SLACK_WEBHOOK_URL is not set"}
    (try
      (let [resp (post-json (slack-webhook) {:text text})]
        {:ok (= 200 (.statusCode resp)) :status (.statusCode resp)})
      (catch Exception e {:ok false :error (.getMessage e)}))))

(defn notify!
  "Push a notification to the user's devices via the exe.dev notify service."
  [title body]
  (try
    (let [resp (post-json (notify-url) {:title title :body body})]
      {:ok (< (.statusCode resp) 400) :status (.statusCode resp)})
    (catch Exception e {:ok false :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Inbound Slack

(defn- hmac-sha256-hex [secret s]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256"))
    (apply str (map #(format "%02x" %) (.doFinal mac (.getBytes ^String s "UTF-8"))))))

(defn valid-signature?
  "Slack signs `v0:<timestamp>:<raw-body>`. A stale timestamp is a replay."
  [timestamp body signature]
  (let [secret (slack-secret)]
    (and secret
         timestamp signature
         (try
           (let [age (Math/abs (- (System/currentTimeMillis)
                                  (* 1000 (Long/parseLong timestamp))))]
             (and (< age (* 5 60 1000))
                  (= signature (str "v0=" (hmac-sha256-hex secret (str "v0:" timestamp ":" body))))))
           (catch Exception _ false)))))

(defn handle-event
  "Handle one Slack Events API delivery. Returns [status body].

  `on-message` is called with {:channel, :user, :text, :ts} for real messages."
  [{:keys [headers body on-message]}]
  (let [ts (get headers "x-slack-request-timestamp")
        sig (get headers "x-slack-signature")
        parsed (try (json/parse-string body true) (catch Exception _ nil))]
    (cond
      (nil? parsed) [400 {:error "bad json"}]
      (not (valid-signature? ts body sig)) [401 {:error "bad signature"}]
      (= "url_verification" (:type parsed)) [200 {:challenge (:challenge parsed)}]
      (and (= "event_callback" (:type parsed))
           (= "message" (get-in parsed [:event :type]))
           (nil? (get-in parsed [:event :bot_id])))
      (do (when on-message
            (on-message (select-keys (:event parsed) [:channel :user :text :ts])))
          [200 {:ok true}])
      :else [200 {:ok true :ignored true}])))

(defn available []
  {:slack_outbound (some? (slack-webhook))
   :slack_inbound (some? (slack-secret))
   :notify true})
