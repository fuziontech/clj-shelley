(ns shelley.btw
  "Side questions about a conversation — \"btw, why did you pick that library?\".

  The problem this solves: a question about the work is not part of the work. If
  you ask it in the main thread, it enters the transcript, becomes context, and
  the agent starts treating the question as a task. So a btw is its own
  conversation, whose system prompt contains a *frozen* snapshot of the parent's
  transcript taken at the moment you asked.

  Frozen, not live, and that is the design decision worth keeping: if the parent
  keeps working while you read the side answer, a live view would drift and the
  answer would be about a different conversation than the one you asked about.
  The snapshot is stamped with where it came from so the answer can say so.

  Storage is intentionally dull — a conversation with `kind: \"btw\"` and a
  parent pointer in its options. No new tables, no new lifecycle."
  (:require [clojure.string :as str]
            [shelley.db :as db]))

(def ^:private default-limit 30)

(def reference-begin "===== BEGIN FROZEN PARENT REFERENCE =====")
(def reference-end "===== END FROZEN PARENT REFERENCE =====")

(defn- block->text
  "One line for a content block. Tool calls and results are summarised: a btw
  reader needs to know what happened, not the bytes."
  [{:keys [type text thinking name input content is-error]}]
  (case type
    :text (when-not (str/blank? (str text)) (str text))
    :thinking (when-not (str/blank? (str thinking)) (str "(thinking) " thinking))
    :tool-use (str "(called " (or name "a tool") ")"
                   (when (and (map? input) (:command input))
                     (let [c (str (:command input))]
                       (str ": " (if (> (count c) 120) (str (subs c 0 120) "…") c)))))
    ;; Tool *output* is deliberately absent. A side question is about what the
    ;; agent did and why, and the raw output of a command is both huge and
    ;; rarely the answer; it would crowd out the conversation.
    :tool-result nil
    :image "[image omitted]"
    nil))

(defn- message->text [m]
  (let [role (case (:type m) :agent "AGENT" :user "USER" :system "SYSTEM" "TOOL")
        lines (keep block->text (:content m))]
    (when (seq lines)
      (str role ": " (str/join " " lines)))))

(defn frozen-reference
  "Render the last `limit` messages of `messages` as a frozen transcript.

  Tool-result-only messages are dropped: a conversation is legible without them
  and they dominate the token count."
  ([messages] (frozen-reference messages default-limit))
  ([messages limit]
   (let [rendered (->> messages
                       (filter #(contains? #{:user :agent :system} (:type %)))
                       (keep message->text)
                       vec)
         tail (if (> (count rendered) limit)
                (concat [(str "(earlier messages omitted: "
                              (- (count rendered) limit) ")")]
                        (subvec rendered (- (count rendered) limit)))
                rendered)]
     (str reference-begin "\n"
          (str/join "\n\n" tail)
          "\n" reference-end))))

(defn system-prompt
  "The system prompt for a btw reader: answer about the transcript, do not
  continue it."
  [reference]
  (str "You are answering a side question about a coding session between a user and "
       "an AI agent. The transcript you may refer to is frozen below: it is what had "
       "happened when the question was asked, and it will not change while you answer.\n\n"
       "Rules:\n"
       "- Answer the question. Do not continue the session's work, and do not start new work.\n"
       "- You may read files and run read-only commands to check facts, but do not modify anything.\n"
       "- If the transcript does not contain the answer, say so; do not guess.\n"
       "- Be brief. This is a side question, not a task.\n\n"
       reference))

(defn options
  "Conversation options that mark this as a btw and point at its parent."
  [parent-id parent-message-id]
  {:kind "btw"
   :parent_pointer {:conversation_id parent-id
                    :message_id parent-message-id}})

(defn btw?
  "True when a conversation row is a btw reader."
  [conv]
  (= "btw" (get-in conv [:options :kind])))

(defn parent-id
  "The conversation a btw belongs to, or nil."
  [conv]
  (get-in conv [:options :parent_pointer :conversation_id]))

(defn create!
  "Create the btw conversation, returning the stored row."
  [{:keys [parent-id parent-message-id model cwd title]}]
  (db/create-conversation! {:parent-id parent-id
                            :user-initiated false
                            :model model
                            :cwd cwd
                            :title (or title "btw")
                            :options (options parent-id parent-message-id)}))

(defn summarize-prompt
  "Ask for the one-or-two-sentence version, for showing in the main thread."
  [question answer]
  (str "Summarise this side question and answer in at most two sentences, for someone "
       "scrolling the main conversation. No preamble.\n\n"
       "Question: " question "\n\nAnswer: " answer))

(defn dismiss-options
  "Mark a btw dismissed: it stays in the database, it just stops being shown."
  [conv]
  (assoc (:options conv) :dismissed true))

(defn active?
  "A btw that should be listed for its parent."
  [conv]
  (and (btw? conv) (not (get-in conv [:options :dismissed]))))
