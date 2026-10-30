(ns shelley.tools.compact
  "compact_in_place: the agent compacts its own context.

  Automatic compaction (`shelley.compact`) happens when history outgrows the
  window and is always a blunt cut. This tool is the opposite: the agent reads
  an index of its own history and says which parts no longer matter, so the
  summary it keeps is the one *it* needs to keep working. It runs three-step,
  exactly like the original:

    action index    list the compactable rows and explain the format
    action compact  collapse ranges into notes, trim tool outputs
    action skip     nothing to compact; the loop drops the nudges

  The handler does not mutate history. It returns a *directive* on the tool
  result for the loop to act on, because history belongs to the loop:

    {:directive {:compact {:action   :compact
                           :trim     [row-id ...]
                           :collapse [{:from row-id :to row-id :note text}]}}}

  A loop that receives this should replace the rows between each collapse's
  :from and :to (inclusive) with one message carrying the note, replace the
  content of each trimmed row's tool results with a short marker, and continue
  the turn on the compacted history. `apply-directive` is that transformation,
  written so the loop does not have to invent it.

  If ctx carries `:apply-compact` (fn [directive] -> text), the handler calls
  it instead of returning a directive, which lets a server-side compactor own
  persistence."
  (:require [clojure.string :as str]
            [shelley.llm :as llm]))

(def gist-length 120)
(def min-compactable-messages 6)

;; ---------------------------------------------------------------------------
;; Index

(defn- row-id [message index]
  (str (or (:sequence-id message) (:sequence_id message) (:id message) index)))

(defn- gist [message]
  (let [s (->> (:content message)
               (map (fn [b]
                      (case (:type b)
                        :text (str (:text b))
                        :thinking "[thinking]"
                        :tool-use (str "[call " (:name b) " " (pr-str (:input b)) "]")
                        :tool-result (str "[result " (count (:content b)) " blocks]")
                        :image "[image]"
                        (str "[" (name (or (:type b) :unknown)) "]"))))
               (str/join " ")
               str/trim)
        s (str/replace s #"\s+" " ")]
    (if (> (count s) gist-length) (str (subs s 0 gist-length) "…") s)))

(defn index-rows
  "The compactable part of `messages`: everything but the system rows and the
  most recent `keep-recent` messages, which the loop needs verbatim."
  [messages & {:keys [keep-recent] :or {keep-recent 2}}]
  (let [messages (vec messages)
        cut (max 0 (- (count messages) keep-recent))]
    (->> (subvec messages 0 cut)
         (map-indexed (fn [i m] m))
         (map-indexed (fn [i m]
                        {:id (row-id m i)
                         :index i
                         :role (name (or (:role m) :user))
                         :bytes (count (pr-str (:content m)))
                         :gist (gist m)})))))

(defn index-text
  "The index the agent reads. Rows are one line each, so a long conversation
  still fits in a tool result."
  [messages]
  (let [rows (index-rows messages)]
    (if (< (count (vec messages)) min-compactable-messages)
      (str "Nothing to compact: this conversation has " (count (vec messages))
           " messages and compaction needs at least " min-compactable-messages ".")
      (str
       "Index of compactable history (oldest first). The last " 2
       " messages are protected and not listed.\n\n"
       (str/join "\n"
                 (for [{:keys [id role bytes gist]} rows]
                   (format "%s  %-9s %6dB  %s" id role bytes gist)))
       "\n\nHow to compact:\n"
       "- call this tool with action \"compact\" and:\n"
       "  trim:     ids whose tool outputs can be replaced by a short marker\n"
       "  collapse: ranges {from, to, note} replaced by one message carrying\n"
       "            the note — what still matters from that range\n"
       "- keep anything you still need verbatim: file paths, decisions, the\n"
       "  user's constraints, and the current plan.\n"
       "- if nothing is worth compacting, call action \"skip\"."
       "\n\nTotal compactable bytes: " (reduce + 0 (map :bytes rows))))))

;; ---------------------------------------------------------------------------
;; Validation

(defn- norm-id [x]
  (cond (nil? x) nil (keyword? x) (name x) :else (str x)))

(defn validate-input
  "Check a compact input against the current index. Returns
  {:ok directive} or {:error text}. Every id must exist: a typo that silently
  compacts nothing would leave the agent believing it had saved context."
  [messages {:keys [trim collapse]}]
  (let [rows (index-rows messages)
        ids (set (map :id rows))
        trim (mapv norm-id (or trim []))
        collapse (vec (or collapse []))
        bad-trim (remove ids trim)
        bad-range (->> collapse
                       (remove (fn [{:keys [from to]}] (and (ids (norm-id from)) (ids (norm-id to))))))
        inverted (->> collapse
                      (filter (fn [{:keys [from to]}]
                                (let [i (some #(when (= (:id %) (norm-id from)) %) rows)
                                      j (some #(when (= (:id %) (norm-id to)) %) rows)]
                                  (and i j (> (:index i) (:index j)))))))
        ;; Ranges *without* a note are the problem; keeping the inverse here
        ;; would reject every well-formed range.
        noteless (filter #(str/blank? (str (:note %))) collapse)]
    (cond
      (and (empty? trim) (empty? collapse))
      {:error "nothing to do: set trim, collapse, or use action \"skip\""}

      (seq bad-trim) {:error (str "no such ids in trim: " (str/join ", " bad-trim))}
      (seq bad-range) {:error (str "collapse ranges name ids that are not in the index: "
                                  (str/join ", " (map (fn [r] (str (:from r) "-" (:to r))) bad-range)))}
      (seq inverted) {:error "collapse ranges must run from older to newer"}
      (seq noteless) {:error "every collapse range needs a note saying what still matters"}
      :else {:ok {:action :compact
                  :trim trim
                  :collapse (mapv (fn [{:keys [from to note]}]
                                    {:from (norm-id from) :to (norm-id to) :note (str note)})
                                  collapse)}})))

;; ---------------------------------------------------------------------------
;; Applying (for the loop)

(def ^:private trimmed-marker "[tool output trimmed by compact_in_place]")

(defn- trim-message [m]
  (if (:compacted m)
    m
    (update m :content
            (fn [content]
              (mapv (fn [b]
                      (if (= :tool-result (:type b))
                        (assoc b :content [(llm/text-block trimmed-marker)])
                        b))
                    content)))))

(defn apply-directive
  "Transform `messages` according to a compact directive.

  Returns the new message vector. Row ids are matched the same way the index
  built them (sequence id when present, else position), so a caller that keeps
  position-based ids stable between the index call and the compact call gets
  the intended result.

  Each collapse range becomes one `:compacted` message carrying its note, at
  the position of the range's oldest row."
  [messages {:keys [trim collapse]}]
  (let [messages (vec messages)
        rows (index-rows messages)
        by-id (into {} (map (juxt :id identity)) rows)
        row-by-index (into {} (map (juxt :index identity)) rows)
        trim-set (set (map norm-id trim))
        ranges (vec (keep (fn [{:keys [from to note]}]
                            (let [a (:index (by-id (norm-id from)))
                                  b (:index (by-id (norm-id to)))]
                              (when (and a b (<= a b)) {:start a :end b :note (str note)})))
                          collapse))
        range-at (fn [i] (some #(when (= i (:start %)) %) ranges))
        in-range? (fn [i] (boolean (some #(<= (:start %) i (:end %)) ranges)))]
    (loop [i 0 out []]
      (if (>= i (count messages))
        out
        (cond
          (range-at i)
          (let [{:keys [end note]} (range-at i)]
            (recur (inc end)
                   (conj out {:role :user
                              :content [(llm/text-block (str "[compacted: " note "]"))]
                              :compacted true})))

          (in-range? i) (recur (inc i) out)

          :else
          (let [m (nth messages i)
                row (get row-by-index i)]
            (recur (inc i)
                   (conj out (if (and row (trim-set (:id row)))
                               (trim-message m)
                               m)))))))))

;; ---------------------------------------------------------------------------
;; Handler

(defn- messages-of [ctx]
  (let [m (:messages ctx)]
    (cond
      (fn? m) (vec (m))
      (instance? clojure.lang.IDeref m) (vec @m)
      (vector? m) m
      (seq? m) (vec m)
      :else nil)))

(defn run-compact-in-place
  [ctx {:keys [action] :as args}]
  (let [no-history
        {:is-error true
         :content [{:type :text
                    :text (str "compact_in_place needs the loop to pass the conversation "
                               "history in ctx as :messages (a vector, atom or fn). This session "
                               "did not, so nothing can be indexed.")}]}]
    (case (str action)
      "index"
      (if-let [messages (messages-of ctx)]
        {:content [{:type :text :text (index-text messages)}]}
        no-history)

      "compact"
      (if-let [messages (messages-of ctx)]
        (let [res (validate-input messages args)]
          (if-let [err (:error res)]
            {:is-error true :content [{:type :text :text err}]}
            (let [directive (:ok res)]
              (if-let [apply-fn (:apply-compact ctx)]
                {:content [{:type :text :text (str (apply-fn directive))}]}
                {:content [{:type :text
                            :text (str "Compaction accepted: trimming " (count (:trim directive))
                                       " row(s) and collapsing " (count (:collapse directive))
                                       " range(s). The loop applies it to the running history.")}]
                 :directive {:compact directive}}))))
        no-history)

      "skip"
      {:content [{:type :text :text "Skipped: nothing was compacted. The nudges are dropped."}]
       :directive {:compact {:action :skip :trim [] :collapse []}}}

      {:is-error true
       :content [{:type :text :text (str "unknown action " (pr-str action) "; use index, compact or skip")}]})))
