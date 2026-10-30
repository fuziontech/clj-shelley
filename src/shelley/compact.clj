(ns shelley.compact
  "Context compaction.

  A coding conversation grows without bound: every tool result stays in the
  history forever. When the estimate crosses the model's limit the turn would
  simply fail, so instead we replace everything except the recent tail with a
  summary written by a cheap model call.

  Kept deliberately outside `shelley.loop` so the loop stays testable without
  a network and the summariser can be swapped."
  (:require [shelley.llm :as llm]
            [clojure.string :as str]))

(def default-keep-messages
  "How much recent history survives a compaction untouched. Large enough that
  the model keeps working context, small enough to actually free space."
  8)

(defn- transcript [messages]
  (str/join "\n\n"
            (for [{:keys [role content]} messages]
              (str (str/upper-case (name role)) ": "
                   (str/join "\n"
                             (for [b content]
                               (case (:type b)
                                 :text (:text b)
                                 :tool-use (str "[called " (:name b) " " (pr-str (:input b)) "]")
                                 :tool-result (str "[result: "
                                                   (subs (str (str/join "\n" (keep :text (:content b))))
                                                         0 (min 400 (count (str (str/join "\n" (keep :text (:content b)))))))
                                                   "]")
                                 :image "[image]"
                                 "")))))))

(defn summarize
  "Ask a model to compress `messages` into a handover note."
  [messages {:keys [model max-tokens]
             :or {max-tokens 4000}}]
  (let [resp (llm/complete
              {:model model
               :system ["You compress a long agent transcript into a handover note. Preserve: "
                        "the user's actual goal, decisions made, files created or modified with "
                        "their paths, commands that failed and why, and anything still outstanding. "
                        "Drop pleasantries and superseded plans. Write dense prose, no preamble."]
               :messages [(llm/user-message (transcript messages))]
               :max-tokens max-tokens})]
    (str/join "\n" (keep :text (:content resp)))))

(defn compact-history
  "Replace all but the recent tail with a summary message.

  Returns the new message vector. Never returns something that would orphan a
  tool result: the cut point is moved back to a user/tool-result boundary."
  ([messages opts] (compact-history messages opts default-keep-messages))
  ([messages {:keys [model] :as opts} keep-n]
   (if (<= (count messages) (inc keep-n))
     messages
     (let [cut (- (count messages) keep-n)
           ;; Do not split a tool call from its results.
           cut (loop [i cut]
                 (if (and (pos? i)
                          (= :assistant (:role (nth messages i)))
                          (seq (llm/tool-uses (nth messages i))))
                   (recur (dec i))
                   i))
           older (subvec (vec messages) 0 cut)
           recent (subvec (vec messages) cut)
           summary (try (summarize older (assoc opts :model model))
                        (catch Exception e
                          (str "(earlier conversation could not be summarised: "
                               (.getMessage e) ")")))]
       (into [{:role :user
               :content [(llm/text-block (str "Summary of the earlier conversation:\n\n" summary))]
               :compacted true
               :compacted-count (count older)}]
             recent)))))
