(ns shelley.tools.message-user
  "message_user: the agent talking to the user in a chat-shaped UI.

  In a chat conversation the user sees their own messages and whatever the
  agent sends through this tool; the agent's ordinary output is hidden. That
  makes this tool the only channel that is guaranteed to be read, so it is
  off by default (a coding agent in a transcript UI should not need it) and
  turned on for chat surfaces.

  Two things it can do that a plain text block cannot:

  - reply to or react to a specific user message (`:reply-to`), which the UI
    renders as a threaded reply or an emoji reaction;
  - ask a question and *wait* for the answer. The wait is a promise the
    transport resolves: the handler emits `:message-user-question` with an
    id, and the server calls `answer!` with that id when the user replies.

  Events emitted through ctx's `:emit`:

    {:type :message-user          ; a message sent to the user
     :message-id uuid :text string :reply-to long-or-nil
     :reaction string-or-nil :attachments [{:path :name :size}]
     :target-excerpt string-or-nil :end-turn boolean}

    {:type :message-user-question ; the agent is waiting for an answer
     :question-id uuid :text string :options [string]}

    {:type :message-user-question-answered ; the wait ended
     :question-id uuid :answer string-or-nil :timed-out boolean}

  A transport that does not implement `answer!` still works: the question
  times out and the agent is told so, which is far better than a thread
  parked forever."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-question-timeout-ms 300000)

(defonce ^:private pending-questions (atom {}))

;; ---------------------------------------------------------------------------
;; Emoji

(def ^:private punctuation-emoji (set "‼⁉〰〽"))

(defn- emoji-codepoint? [cp]
  (let [type (Character/getType (int cp))]
    (or (= type (int Character/OTHER_SYMBOL))     ; ☀ 👍 🚀, most emoji
        (= type (int Character/OTHER_PUNCTUATION)) ; only with the set below
        (= cp 0x20E3)                              ; combining enclosing keycap
        (= cp 0x200D)                              ; zero width joiner (👩‍💻)
        (= cp 0xFE0F)                              ; variation selector-16
        (<= 0x1F1E6 cp 0x1F1FF)                    ; regional indicators (flags)
        (<= 0x1F300 cp 0x1FAFF)                    ; symbols and pictographs
        (<= 0x2600 cp 0x27BF)                      ; misc symbols and dingbats
        (<= 0x1F000 cp 0x1F0FF)                    ; mahjong and playing cards
        (contains? punctuation-emoji (char cp)))))

(defn single-emoji?
  "Plausibly one emoji: a short cluster built on an emoji code point.

  Deliberately permissive — a full grapheme-cluster segmentation is not worth
  a dependency here, and the failure directions are asymmetric: a wrong yes is
  a reaction the UI may not render, a wrong no is a model that cannot react at
  all. The length cap is what stops it accepting a sentence."
  [s]
  (let [s (str s)]
    (and (not (str/blank? s))
         (<= (count s) 12)                 ; chars, not code points: generous
         (every? emoji-codepoint? (.toArray (.codePoints s))))))

;; ---------------------------------------------------------------------------
;; Attachments

(defn- attachment [cwd p]
  (when (str/blank? (str p))
    (throw (ex-info "empty attachment path; nothing was sent" {:kind :bad-args})))
  (let [f (io/file (str p))
        f (if (.isAbsolute f) f (io/file (str cwd) (str p)))
        f (.getCanonicalFile f)]
    (when-not (.exists f)
      (throw (ex-info (str "attachment " (.getPath f) " does not exist; nothing was sent")
                      {:kind :not-found})))
    (when-not (.isFile f)
      (throw (ex-info (str "attachment " (.getPath f) " is not a regular file; nothing was sent")
                      {:kind :not-a-file})))
    {:path (.getPath f) :name (.getName f) :size (.length f)}))

;; ---------------------------------------------------------------------------
;; Questions

(defn answer!
  "Resolve a pending question. The server calls this when the user replies.
  Returns true when a waiting question took the answer, false when the id is
  unknown or already answered."
  [question-id text]
  (if-let [p (get @pending-questions (str question-id))]
    (do (swap! pending-questions dissoc (str question-id))
        (deliver p (str text))
        true)
    false))

(defn pending-question-ids [] (vec (keys @pending-questions)))

(defn- ask! [ctx {:keys [question options timeout-ms]}]
  (let [id (str (java.util.UUID/randomUUID))
        p (promise)
        timeout (long (or timeout-ms (:message-user-timeout-ms ctx) default-question-timeout-ms))
        emit (:emit ctx)]
    (swap! pending-questions assoc id p)
    (when emit (emit {:type :message-user-question
                      :question-id id
                      :text (str question)
                      :options (vec (or options []))}))
    (let [answer (deref p timeout ::timeout)]
      (swap! pending-questions dissoc id)
      (when emit (emit {:type :message-user-question-answered
                        :question-id id
                        :answer (when-not (= ::timeout answer) answer)
                        :timed-out (= ::timeout answer)}))
      (if (= ::timeout answer)
        (str "No reply from the user within " (quot timeout 1000)
             "s. Do not ask again; say what you would need and stop, or continue with a stated assumption.")
        (str "The user replied: " answer)))))

;; ---------------------------------------------------------------------------
;; Handler

(defn run-message-user
  [ctx {:keys [text reply_to reaction attachments end_turn question options timeout_ms]
        :as _args}]
  (let [cwd (or (some-> (:working-dir ctx) deref) (System/getProperty "user.dir"))
        text (str/trim (str (or text "")))
        reaction (str/trim (str (or reaction "")))
        question (some-> question str str/trim not-empty)
        reply-to (when (and reply_to (pos? (long reply_to))) (long reply_to))
        emit (:emit ctx)]
    (when (and (str/blank? text) (str/blank? reaction) (empty? attachments) (nil? question))
      (throw (ex-info "nothing to send: set text, reaction, attachments, or question"
                      {:kind :bad-args})))
    (when (and (seq reaction) (nil? reply-to))
      (throw (ex-info "a reaction needs reply_to, the id of the message to react to"
                      {:kind :bad-args})))
    (when (and (seq reaction) (not (single-emoji? reaction)))
      (throw (ex-info (str "reaction must be a single emoji, got " (pr-str reaction))
                      {:kind :bad-args})))
    (let [atts (mapv #(attachment cwd %) (or attachments []))
          id (str (java.util.UUID/randomUUID))
          quote-of (fn [s] (let [s (str/replace (str s) #"\s+" " ")]
                              (str "\"" (if (> (count s) 80) (str (subs s 0 80) "…") s) "\"")))
          ;; The transport renders replies and reactions; it resolves the
          ;; excerpt in its own words, so we only pass the id through.
          _ (when emit
              (emit {:type :message-user
                     :message-id id
                     :text text
                     :reply-to reply-to
                     :reaction (not-empty reaction)
                     :attachments atts
                     :end-turn (boolean end_turn)}))
          sent (cond
                 (and (str/blank? text) (empty? atts) (seq reaction))
                 (str "Reacted " reaction " to message " reply-to ".")

                 reply-to
                 (str "Sent, in reply to message " reply-to "."
                      (when (seq reaction) (str " Also reacted " reaction ".")))

                 :else "Sent.")]
      {:content [{:type :text :text (str sent
                                          (when (seq atts)
                                            (str "\nAttached: " (str/join ", "
                                                                         (map (fn [a] (str (:name a) " (" (:size a) " bytes)"))
                                                                              atts))))
                                          (when question (str "\n" (ask! ctx {:question question
                                                                                :options options
                                                                                :timeout-ms timeout_ms}))))}]
       :ui {:type :message-user :message-id id}})))

(defn reset-questions!
  "Test helper: drop every pending question without answering it."
  []
  (reset! pending-questions {}))
