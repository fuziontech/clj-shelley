(ns shelley.server.sse
  "The two streams and the conversation-list patch protocol behind them.

  `/api/stream2` publishes the conversation list as a sequence of RFC-6902
  patches: each event carries the hash of the list before and after, so a
  client can tell whether it missed one and refetch. The hash is a SHA-256 of
  the rows as JSON, which makes it a property of the data rather than of the
  event history.

  `/api/conversation/:id/stream` replays the transcript in one `history` frame
  and then forwards live turn events for that conversation.

  Both streams are plain SSE with the event type inside the JSON payload. The
  per-conversation stream also sets an SSE `id` of `seq:<n>` (n is the
  conversation's last persisted sequence id at the time), which is what makes
  `Last-Event-ID` meaningful: on reconnect the replay starts after it."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [shelley.db :as db]
            [shelley.server.http :as http]
            [shelley.server.models :as models]
            [shelley.server.rows :as rows]
            [shelley.server.runtime :as runtime]
            [org.httpkit.server :as server])
  (:import (java.security MessageDigest)))

;; ---------------------------------------------------------------------------
;; Hashing

(defn- sha256 [s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn list-hash
  "A stable hash of the rows. Sorting keys means the hash depends on the data,
  not on the order the JSON encoder happened to walk the maps."
  [conversation-rows]
  (sha256 (json/generate-string conversation-rows {:key-fn identity})))

;; ---------------------------------------------------------------------------
;; Diffing

(def ^:private key-field "conversation_id")

(defn- row-key
  "Rows go through JSON on their way to a client but are Clojure maps here, and
  a map keyed by keywords must hash the same as one keyed by strings."
  [row]
  (str (or (get row key-field) (get row (keyword key-field)) "")))

(defn- ptr-escape [s]
  (-> (str s)
      (str/replace "~" "~0")
      (str/replace "/" "~1")))

(defn- field-ops [index old-row new-row]
  (let [fields (distinct (concat (keys old-row) (keys new-row)))]
    (vec
     (keep (fn [f]
             (let [o (contains? old-row f) n (contains? new-row f)
                   ov (get old-row f) nv (get new-row f)
                   path (str "/" index "/" (ptr-escape f))]
               (cond
                 (and o (not n)) {:op "remove" :path path}
                 (and n (not o)) {:op "add" :path path :value nv}
                 (not= ov nv) {:op "replace" :path path :value nv}
                 :else nil)))
           fields))))

(defn- remove-ops
  [cur new-by ops]
  (if-let [gone (first (keep-indexed (fn [i r] (when-not (contains? new-by (row-key r)) i)) cur))]
    (recur (vec (concat (subvec cur 0 gone) (subvec cur (inc gone))))
           new-by
           (conj ops {:op "remove" :path (str "/" gone)}))
    [cur ops]))

(defn- order-ops
  "Walk the target order left to right, moving or inserting whatever belongs at
  each index. Moves only ever come from the right, so the prefix already placed
  is never disturbed."
  [cur target-order new-by ops]
  (loop [cur cur i 0 ops ops]
    (if (>= i (count target-order))
      [cur ops]
      (let [want (nth target-order i)]
        (cond
          (>= i (count cur))
          (recur (conj cur (get new-by want)) (inc i)
                 (conj ops {:op "add" :path (str "/" i) :value (get new-by want)}))

          (= (row-key (nth cur i)) want)
          (recur cur (inc i) ops)

          :else
          (let [j (first (keep-indexed (fn [k r] (when (= (row-key r) want) k)) (subvec cur i)))]
            (if (nil? j)
              (recur (vec (concat (subvec cur 0 i) [(get new-by want)] (subvec cur i)))
                     (inc i)
                     (conj ops {:op "add" :path (str "/" i) :value (get new-by want)}))
              (let [j (+ i j)
                    moved (nth cur j)
                    without (vec (concat (subvec cur 0 j) (subvec cur (inc j))))]
                (recur (vec (concat (subvec without 0 i) [moved] (subvec without i)))
                       (inc i)
                       (conj ops {:op "move" :from (str "/" j) :path (str "/" i)}))))))))))

(defn diff-rows
  "RFC-6902 operations that turn `old-rows` into `new-rows`.

  Removals run first so the index space settles, then one left-to-right pass
  puts each row in place with a `move` or an `add`, then changed fields are
  replaced individually. Sending one op per changed field rather than a whole
  replaced row keeps a rename from looking like a new conversation."
  [old-rows new-rows]
  (let [target-order (mapv row-key new-rows)
        new-by (into {} (map (fn [r] [(row-key r) r])) new-rows)
        old-by (into {} (map (fn [r] [(row-key r) r])) old-rows)
        [cur ops] (remove-ops (vec old-rows) new-by [])
        [cur ops] (order-ops cur target-order new-by ops)]
    (reduce (fn [ops i]
              (let [new-row (nth new-rows i)
                    old-row (get old-by (row-key new-row))]
                ;; A row that was just added arrives whole; diffing it against
                ;; nothing would emit an op per field on top of the add.
                (if old-row (into ops (field-ops i old-row new-row)) ops)))
            ops
            (range (count new-rows)))))

;; ---------------------------------------------------------------------------
;; Patch state

(defonce ^:private patch-state
  (atom {:rows [] :hash nil :events [] :seq 0}))

(def ^:private max-history 100)

(defn- patch-event
  [old-hash new-hash ops & {:keys [reset]}]
  (http/compact-map
   {"type" "conversation_list_patch"
    "old_hash" old-hash
    "new_hash" new-hash
    "patch" ops
    "reset" (or reset false)
    "at" (http/now-iso)}))

(defn recompute!
  "Re-read the list, diff it against the last published version, and publish.

  Every mutation funnels through here, so a burst of changes costs one query per
  change and produces one patch per change; there is no debounce because a
  missed patch is a stale sidebar until the next event."
  []
  (let [new-rows (rows/snapshot-rows)
        new-hash (list-hash new-rows)
        {:keys [rows hash]} @patch-state]
    (if (= new-hash hash)
      nil
      (let [reset? (nil? hash)
            ops (if reset?
                  [{:op "replace" :path "" :value new-rows}]
                  (diff-rows rows new-rows))
            event (patch-event hash new-hash ops :reset reset?)]
        (swap! patch-state (fn [st]
                             (-> st
                                 (assoc :rows new-rows :hash new-hash)
                                 (update :events #(vec (take-last max-history (conj % event))))
                                 (update :seq inc))))
        (runtime/broadcast-global! event)
        event))))

(defn patch-history
  "The patches published so far, oldest first. A reconnecting client can apply
  the ones it missed instead of refetching, and one that cannot tells by hash."
  ([] (:events @patch-state))
  ([since] (let [events (:events @patch-state)
                 idx (some (fn [i] (when (= since (:new_hash (nth events i))) i))
                           (range (count events)))]
             (if idx (subvec events idx) events))))

(defn snapshot
  "Current rows and hash, recomputing first so the hash matches the rows."
  []
  (recompute!)
  (let [{:keys [rows hash]} @patch-state]
    {:conversations rows :hash hash}))

(defn handler-snapshot [_]
  (let [{:keys [conversations hash]} (snapshot)]
    (http/ok {"conversations" conversations
              "hash" hash
              "count" (count conversations)})))

;; Install the hook the runtime calls after any list-changing write.
(reset! runtime/list-change-hook recompute!)

;; ---------------------------------------------------------------------------
;; SSE plumbing

(def ^:private heartbeat-ms 25000)

(defn- start-heartbeat! [ch]
  (future
    (try
      (while (Thread/sleep heartbeat-ms)
        (when-not (server/send! ch ": ping\n\n" false)
          (throw (ex-info "closed" {}))))
      (catch Throwable _ nil))))

(defn- sse-headers []
  {:headers {"content-type" "text/event-stream; charset=utf-8"
             "cache-control" "no-cache, no-transform"
             "connection" "keep-alive"
             "x-accel-buffering" "no"}})

(defn- opening-frame
  "The first send! of an SSE response carries the status and headers.

  `as-channel` takes only the on-* callbacks: the `:headers` of the map it is
  given are ignored, and http-kit falls back to `text/html` unless the first
  sent map carries them. A browser's EventSource refuses text/html, so this is
  the difference between a stream and a stream nothing can read."
  [frame]
  (assoc (sse-headers) :status 200 :body frame))

(defn handle-list-stream
  "GET /api/stream2 — the conversation list, then a patch per change."
  [req]
  (server/as-channel
   req
   (assoc (sse-headers)
          :on-open (fn [ch]
                     ;; Order matters: the history frame must be the first
                     ;; thing this client sees. Recomputing publishes a patch
                     ;; to every subscriber, so it happens before subscribing —
                     ;; any change in that window arrives as a patch straight
                     ;; after the history, which is exactly how a client is
                     ;; meant to reconcile.
                     (let [{:keys [conversations hash]} (snapshot)]
                       (runtime/subscribe-global! ch)
                       (server/send! ch (opening-frame
                                         (runtime/sse-frame
                                          (json/generate-string
                                           {"type" "history"
                                            "conversations" conversations
                                            "hash" hash}))) false))
                     ;; Anything that changed between computing the snapshot
                     ;; and subscribing was published to nobody. One more
                     ;; recompute closes that window: a no-op if nothing moved,
                     ;; a patch this client now receives if something did.
                     (recompute!)
                     (start-heartbeat! ch))
          :on-close (fn [ch] (runtime/unsubscribe-global! ch)))))

(defn- parse-last-sequence
  "Read the resume point from `Last-Event-ID`, `last_event_id`, or
  `last_sequence_id`. The SSE id is `seq:<n>`; a bare integer is accepted
  because the original used one."
  [req]
  (let [raw (or (get-in req [:headers "last-event-id"])
                (get-in req [:headers "Last-Event-ID"])
                (http/param req :last_event_id)
                (http/param req :last_sequence_id))]
    (when-not (str/blank? (str raw))
      (let [s (str/replace (str raw) #"^seq:" "")]
        (try (Long/parseLong (str/trim s)) (catch Exception _ nil))))))

(defn- stream-messages
  "The replay window: everything after the resume point, or the last `tail`
  rows, or the whole transcript."
  [conv-id {:keys [since tail]}]
  (cond
    (some? since) (db/messages-since conv-id since)
    (and tail (pos? tail)) (db/messages-tail conv-id tail)
    :else (db/transcript conv-id)))

(defn handle-conversation-stream
  "GET /api/conversation/:id/stream — replay, then live events."
  [req]
  (let [conv-id (http/path-param req :id)
        conv (db/get-conversation conv-id)
        tail (http/int-param req :tail)
        last-sequence-id (http/int-param req :last_sequence_id)]
    (cond
      (nil? conv) (http/not-found "conversation not found")

      (and tail last-sequence-id)
      (http/bad-request "tail and last_sequence_id are mutually exclusive")

      (and tail (not (pos? tail)))
      (http/bad-request "tail must be a positive integer")

      :else
      (server/as-channel
       req
       (assoc (sse-headers)
              :on-open (fn [ch]
                         ;; Subscribe first: a delta emitted between the query
                         ;; and the subscription would otherwise be lost.
                         (runtime/subscribe! conv-id ch)
                         (let [since (parse-last-sequence req)
                               messages (stream-messages conv-id {:since since :tail tail})
                               max-seq (or (some->> (db/latest-actionable-message conv-id) :sequence-id)
                                           (-> messages last :sequence-id)
                                           0)
                               row (rows/conversation-row (db/get-conversation conv-id))
                               payload {"type" "history"
                                        "conversation_id" conv-id
                                        "messages" (rows/api-messages messages)
                                        "conversation" row
                                        "agent_working" (runtime/running? conv-id)
                                        "running" (runtime/running? conv-id)
                                        "context_window_size" (models/context-window (:model row))
                                        "max_sequence_id" max-seq
                                        "queued_messages" (mapv :text (runtime/queued-messages conv-id))}]
                           (server/send! ch (opening-frame
                                             (runtime/sse-frame (str "seq:" max-seq)
                                                                (json/generate-string payload))) false)
                           (server/send! ch (runtime/sse-frame
                                             (json/generate-string {"type" "snapshot_complete"
                                                                   "conversation_id" conv-id}))
                                         false))
                         (start-heartbeat! ch))
              :on-close (fn [ch] (runtime/unsubscribe! conv-id ch)))))))

(def routes
  [["/api/stream2" {:get handle-list-stream}]
   ["/api/conversations/snapshot" {:get handler-snapshot}]
   ["/api/stream" {:get handle-list-stream}]])
