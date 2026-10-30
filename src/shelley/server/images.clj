(ns shelley.server.images
  "Uploads and per-message image bytes.

  Two directions are needed and they are not the same thing:

  - a user drops a file into the composer, which the client posts here and the
    agent then reads from disk;
  - the transcript already holds an image block whose base64 payload would
    bloat every list request, so `llm_data` carries a URL instead and the bytes
    are served from the message row on demand."
  (:require [clojure.string :as str]
            [shelley.db :as db]
            [shelley.server.http :as http])
  (:import (java.io ByteArrayInputStream File)
           (java.nio.file Files Path Paths StandardCopyOption)
           (java.util Base64 UUID)))

(def upload-dir
  (or (System/getenv "CLJ_SHELLEY_UPLOAD_DIR") "/tmp/shelley-uploads"))

(def max-upload-bytes (* 25 1024 1024))

(defn- safe-basename [name]
  (let [base (-> (str (or name "upload"))
                 (str/replace #"^.*[/\\]" "")
                 (str/replace #"[^\w.\- ]" "_")
                 str/trim)]
    (if (or (str/blank? base) (= base ".") (= base "..")) "upload" base)))

(defn- unique-target [dir base]
  (let [f (File. dir base)]
    (if (.exists f)
      (let [dot (str/last-index-of base ".")
            stem (if dot (subs base 0 dot) base)
            ext (if dot (subs base dot) "")]
        (loop [n 1]
          (let [candidate (File. dir (str stem "-" n ext))]
            (if (.exists candidate) (recur (inc n)) candidate))))
      f)))

(defn save-bytes!
  "Write `bytes` into the upload directory under a sanitised, non-colliding
  name and return the absolute path. Refuses to escape the directory: the name
  is reduced to a basename first."
  [filename ^bytes bytes]
  (when (> (alength bytes) max-upload-bytes)
    (throw (ex-info "file too large" {:type :too-large})))
  (let [dir (File. upload-dir)]
    (.mkdirs dir)
    (let [target (unique-target dir (safe-basename filename))]
      (with-open [out (java.io.FileOutputStream. target)]
        (.write out bytes))
      (.getAbsolutePath target))))

(defn- decode-base64 [s]
  (let [s (str/replace (str s) #"^data:[^,]*;base64," "")]
    (.decode (Base64/getDecoder) ^String (str/replace s #"\s" ""))))

(defn- data-url? [s] (and (string? s) (str/starts-with? s "data:")))

;; ---------------------------------------------------------------------------
;; Uploads

(defn handle-upload
  "POST /api/upload — `{name, content}` where content is base64 or a data URL.
  `{dataUrl}` alone is accepted because that is what a paste produces."
  [req]
  (let [body (http/parse-body req)
        name (or (:name body) (:filename body) "paste.png")
        content (or (:content body) (:dataUrl body) (:data_url body))]
    (if (str/blank? (str content))
      (http/bad-request "content is required")
      (try
        (let [bytes (decode-base64 content)
              path (save-bytes! name bytes)]
          (http/ok {"path" path
                    "name" (safe-basename name)
                    "size" (alength bytes)}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :too-large (:type (ex-data e)))
            (http/too-large "file too large")
            (http/internal-error (.getMessage e))))
        (catch IllegalArgumentException _
          (http/bad-request "content is not valid base64"))))))

(defn handle-upload-raw
  "POST /api/upload/raw?filename=... — the request body is the file itself."
  [req]
  (let [name (http/param req :filename)]
    (if (str/blank? (str name))
      (http/bad-request "filename required")
      (try
        (let [bytes (http/raw-body req)
              path (save-bytes! name bytes)]
          (http/ok {"path" path "name" (safe-basename name) "size" (alength bytes)}))
        (catch clojure.lang.ExceptionInfo e
          (if (= :too-large (:type (ex-data e)))
            (http/too-large "file too large")
            (http/internal-error (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Message images

(def ^:private type->content-type
  {"png" "image/png" "jpeg" "image/jpeg" "jpg" "image/jpeg" "gif" "image/gif"
   "webp" "image/webp" "svg+xml" "image/svg+xml" "svg" "image/svg+xml"})

(defn- image-bytes [block]
  (let [data (or (:data block) (:source (:data block)) (:base64 block))
        media (or (:media-type block) (:media_type block) (:mediaType block) "image/png")]
    (when data
      {:bytes (try (decode-base64 data) (catch Exception _ nil))
       :media-type (or (get type->content-type (str/replace (str media) #"^image/" "")) media)})))

(defn handle-message-image
  "GET /api/message/:id/image/:index/:sub — the bytes behind an image block.

  `sub` selects within a multi-image tool result; when it does not address a
  block it is ignored, because the common case is one image."
  [req]
  (let [mid (http/path-param req :id)
        idx (http/int-param req :index 0)
        sub (http/int-param req :sub 0)
        message-id (or mid (http/int-param req :message-id))
        message (when message-id (db/message-by-id message-id))]
    (cond
      (nil? message) (http/not-found "message not found")
      :else
      (let [block (get (vec (:content message)) idx)
            ;; A tool result holds its images inside its content vector.
            block (if (and block (= :tool-result (:type block)))
                    (get (vec (:content block)) sub)
                    block)
            {:keys [bytes media-type]} (when block (image-bytes block))]
        (if (and bytes (pos? (alength ^bytes bytes)))
          {:status 200
           :headers {"content-type" media-type
                     "cache-control" "private, max-age=31536000, immutable"
                     "content-length" (str (alength ^bytes bytes))}
           :body (ByteArrayInputStream. bytes)}
          (http/not-found "no image at that position"))))))

(defn handle-attach
  "POST /api/conversation/:id/attach — record an uploaded file on a message.

  The message store keeps attachments as their own column, so a client can list
  what a user attached without re-reading the transcript."
  [req]
  (let [conv-id (http/path-param req :id)
        body (http/parse-body req)
        message-id (:message_id body)
        attachments (or (:attachments body) (:files body) [])]
    (cond
      (nil? (db/get-conversation conv-id)) (http/not-found "conversation not found")
      (nil? message-id) (http/bad-request "message_id is required")
      :else
      (do
        (db/update-message-user-data! message-id
                                      (merge (or (http/parse-json-object
                                                  (:user-data (db/message-by-id message-id))) {})
                                             {:attachments (vec attachments)}))
        (http/ok {"status" "ok" "attachments" (vec attachments)})))))

(def routes
  [["/api/upload" {:post handle-upload}]
   ["/api/upload/raw" {:post handle-upload-raw}]
   ["/api/conversation/:id/attach" {:post handle-attach}]
   ["/api/message/:message-id/image/:index/:sub" {:get handle-message-image}]])
