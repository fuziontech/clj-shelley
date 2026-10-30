(ns shelley.server.settings
  "Server settings, profiles, and feature flags.

  Three unrelated-looking things share a file because they share a shape: a
  small key/value store, a live default, and a client that reads all three on
  load. Keeping them together makes it obvious that `model` is a setting, not a
  secret, and that a flag is a setting with a default."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [shelley.db :as db]
            [shelley.server.http :as http]
            [shelley.server.models :as models]))

;; ---------------------------------------------------------------------------
;; Settings

(def flag-registry
  "Flags this build knows about: name, what it does, and the value when no
  override is stored. A flag not in this list can still be stored — the UI may
  be newer than the server — but nothing reads it."
  [{:name "performance-hud" :description "Show the performance HUD overlay" :default false}
   {:name "compact-in-place" :description "Allow in-place context compaction" :default true}
   {:name "subagents" :description "Enable the subagent tool" :default true}
   {:name "queue-messages" :description "Allow messages queued during a turn" :default true}])

(def writable-settings
  "Keys POST /api/settings may write. An open key/value endpoint would let a
  client set anything the server later trusts, so the list is explicit."
  #{"auto_upgrade" "exe_notify" "model" "thinking-level" "thinking_level"
    "system_prompt" "tool_overrides" "disable_all_tools" "profile" "theme"})

(defn settings-map
  "Stored settings keyed by name, exactly as the database holds them, with the
  derived values a client needs folded in."
  []
  (let [stored (db/all-settings)]
    (merge {"model" (models/default-model-id)
            "thinking_level" (or (db/get-setting :thinking-level) "off")
            "system_prompt" nil
            "tool_overrides" {}
            "disable_all_tools" false
            "profile" (:name (db/default-profile))}
           (into {} (map (fn [[k v]] [(str/replace (name k) "-" "_") v]) stored))
           {"profile" (or (:name (db/default-profile)) (get stored :profile))})))

(defn handler-get-settings [_]
  (let [m (settings-map)]
    ;; Both shapes on purpose: `settings` is what the UI reads, the top-level
    ;; keys are what a hand-written client guesses. They are kept in step here
    ;; so they cannot drift.
    (http/ok (merge m {"settings" m}))))

(defn- parse-value [v]
  (cond
    (string? v) v
    (nil? v) ""
    :else (json/generate-string v)))

(defn handler-set-setting
  "POST /api/settings, two accepted shapes:

  - `{key, value}` — one setting, the original's shape;
  - `{model, thinking_level, system_prompt, tool_overrides, disable_all_tools}`
    — the group a client saves from the settings panel.
  Also POST /settings at the root, for parity with the original."
  [req]
  (let [body (http/parse-body req)]
    (cond
      (and (contains? body :key) (not (str/blank? (str (:key body)))))
      (let [k (str/replace (str (:key body)) "_" "-")
            writable? (or (contains? writable-settings (str (:key body)))
                          (contains? writable-settings k))]
        (if-not writable?
          (http/bad-request (str "unknown or read-only setting: " (:key body)))
          (do (db/set-setting! k (parse-value (:value body)))
              (http/ok {"status" "ok" "key" (:key body) "value" (:value body)}))))

      (empty? body) (http/bad-request "no settings supplied")

      :else
      (let [writes (for [[k v] body
                         :let [kk (str/replace (name k) "_" "-")]
                         :when (contains? writable-settings (str/replace (name k) "_" "-"))]
                     [kk v])]
        (doseq [[k v] writes]
          (db/set-setting! k (parse-value v)))
        (http/ok (merge (settings-map) {"status" "ok" "updated" (mapv first writes)}))))))

(defn handler-delete-setting [req]
  (let [k (str/replace (str (http/path-param req :key)) "_" "-")]
    (db/delete-setting! k)
    (http/ok {"status" "ok" "key" k})))

;; ---------------------------------------------------------------------------
;; Feature flags

(defn flags-map
  "Flag name -> value in force: the stored override, else the registry default,
  else false."
  []
  (let [stored (into {} (map (fn [[k v]] [(name k) v]) (db/feature-flags)))
        defaults (into {} (map (fn [{:keys [name default]}] [name default]) flag-registry))]
    (merge defaults stored)))

(defn handler-get-feature-flags [_]
  (let [in-force (flags-map)
        stored (into {} (map (fn [[k v]] [(name k) v]) (db/feature-flags)))]
    (http/ok {"flags" in-force
              "registry" (mapv (fn [{:keys [name description default]}]
                                 {"name" name
                                  "description" description
                                  "default" default
                                  "value" (get in-force name default)
                                  "override" (get stored name)})
                               flag-registry)})))

(defn handler-get-feature-flag [req]
  (let [name (http/path-param req :name)]
    (if-let [{:keys [description default]} (some #(when (= name (:name %)) %) flag-registry)]
      (http/ok {"name" name
                "description" description
                "default" default
                "value" (get (flags-map) name default)
                "override" (get (into {} (map (fn [[k v]] [(clojure.core/name k) v]) (db/feature-flags))) name)})
      (http/not-found "unknown feature flag"))))

(defn handler-set-feature-flag [req]
  (let [body (http/parse-body req)
        name (or (http/path-param req :name) (:name body))
        value (cond
                (contains? body :value) (:value body)
                (contains? body :enabled) (http/truthy? (:enabled body))
                :else nil)]
    (if (nil? name)
      (http/bad-request "flag name is required")
      (do (db/set-feature-flag! name value)
          (http/ok {"name" name "value" value "flags" (flags-map)})))))

(defn handler-delete-feature-flag [req]
  (let [name (or (http/path-param req :name) (:name (http/parse-body req)))]
    (db/delete-feature-flag! name)
    (http/ok {"name" name "value" (get (flags-map) name)})))

(defn flag-enabled? [name]
  (http/truthy? (get (flags-map) (clojure.core/name name))))

;; ---------------------------------------------------------------------------
;; Profiles

(defn- profile->api [p]
  (http/compact-map
   {"name" (:name p)
    "display_name" (:display_name p)
    "settings" (:settings p)
    "is_default" (boolean (:is-default p))
    "builtin" (boolean (:builtin p))
    "user_edited" (boolean (:user-edited p))
    "created_at" (http/iso-time (:created_at p))
    "updated_at" (http/iso-time (:updated_at p))}))

(defn handler-list-profiles [_]
  (http/ok {"profiles" (mapv profile->api (db/list-profiles))
            "default" (some-> (db/default-profile) :name)
            "builtin" (db/builtin-profiles)}))

(defn handler-get-profile [req]
  (let [name (http/path-param req :name)]
    (if-let [p (db/get-profile name)]
      (http/ok (profile->api p))
      (http/not-found "profile not found"))))

(defn handler-activate-profile
  "POST /api/profiles/:name — make a profile the default, the way the original's
  POST takes an activation rather than an edit."
  [req]
  (let [name (http/path-param req :name)]
    (if (nil? (db/get-profile name))
      (http/not-found "profile not found")
      (do (db/set-default-profile! name)
          (http/ok (profile->api (db/get-profile name)))))))

(defn handler-create-profile [req]
  (let [body (http/parse-body req)
        name (or (:name body) (http/path-param req :name))]
    (if (str/blank? (str name))
      (http/bad-request "name is required")
      (do (db/create-profile! name (or (:settings body) {}))
          (http/created (profile->api (db/get-profile name)))))))

(defn handler-update-profile [req]
  (let [name (or (http/path-param req :name) (:name (http/parse-body req)))]
    (cond
      (nil? (db/get-profile name)) (http/not-found "profile not found")
      :else (let [body (http/parse-body req)]
              (http/ok (profile->api (db/update-profile-settings! name (:settings body))))))))

(defn handler-delete-profile [req]
  (let [name (http/path-param req :name)]
    (if (zero? (db/delete-profile! name))
      (http/conflict "profile not found, or it is the default profile")
      (http/ok {"status" "deleted" "name" name}))))

(def routes
  [["/api/settings" {:get handler-get-settings :post handler-set-setting}]
   ["/api/settings/:key" {:delete handler-delete-setting}]
   ["/settings" {:get handler-get-settings :post handler-set-setting}]
   ["/api/feature-flags" {:get handler-get-feature-flags
                          :post handler-set-feature-flag
                          :delete handler-delete-feature-flag}]
   ["/api/feature-flags/:name" {:get handler-get-feature-flag
                                :post handler-set-feature-flag
                                :put handler-set-feature-flag
                                :delete handler-delete-feature-flag}]
   ["/feature-flags" {:get handler-get-feature-flags
                      :post handler-set-feature-flag
                      :delete (fn [req]
                                (let [name (or (:name (http/parse-body req))
                                               (:name (http/query-params req)))]
                                  (if (str/blank? (str name))
                                    (http/bad-request "name is required")
                                    (do (db/delete-feature-flag! name)
                                        (http/ok {"name" name
                                                  "value" (get (flags-map) name)})))))}]
   ["/feature-flags/:name" {:post handler-set-feature-flag :delete handler-delete-feature-flag}]
   ["/api/profiles" {:get handler-list-profiles :post handler-create-profile}]
   ["/api/profiles/:name" {:get handler-get-profile
                           :post handler-activate-profile
                           :put handler-update-profile
                           :delete handler-delete-profile}]])
