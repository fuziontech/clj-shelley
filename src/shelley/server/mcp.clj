(ns shelley.server.mcp
  "MCP servers as configuration.

  There is no MCP client in this build, so these endpoints manage rows and say
  so. A stored server is a promise the server cannot keep yet, which is why the
  list marks each one `connected: false` and the tool endpoints return an empty
  tool list rather than a 404: the UI draws a configured-but-idle server
  differently from a missing one."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [shelley.db :as db]
            [shelley.server.http :as http]))

(defn- parse-stdio
  "A stdio spec is stored in the row's `url` column as `stdio:<json>` because
  the schema has one column for how to reach a server and a command is a way to
  reach one. Round-trips without loss."
  [url]
  (when (and url (str/starts-with? (str url) "stdio:"))
    (try (http/parse-json-object (subs (str url) (count "stdio:")))
         (catch Exception _ nil))))

(defn server->api [row]
  (let [stdio (parse-stdio (:url row))]
    (http/compact-map
     {"name" (:name row)
      "url" (when-not stdio (:url row))
      "command" (:command stdio)
      "args" (:args stdio)
      "env" (:env stdio)
      "description" (:description row)
      "headers" (:headers row)
      "connected" false
      "tools" []
      "status" "configured"})))

(defn handler-list [_]
  (http/ok {"servers" (mapv server->api (db/list-mcp-servers))}))

(defn- stdio-url [body]
  (when (or (:command body) (:args body))
    (str "stdio:" (json/generate-string (select-keys body [:command :args :env])))))

(defn handler-save
  "POST /api/mcp/servers — add or replace one server."
  [req]
  (let [body (http/parse-body req)
        name (or (:name body) (http/path-param req :name))
        url (or (:url body) (stdio-url body))]
    (cond
      (str/blank? (str name)) (http/bad-request "name is required")
      (str/blank? (str url)) (http/bad-request "url or command is required")
      :else
      (do (db/save-mcp-server! {:name name
                                :url url
                                :description (or (:description body) "")
                                :headers (or (:headers body) {})})
          (http/ok (server->api (db/get-mcp-server name)))))))

(defn handler-delete [req]
  (let [name (http/path-param req :name)]
    (if (nil? (db/get-mcp-server name))
      (http/not-found "mcp server not found")
      (do (db/delete-mcp-server! name)
          (http/ok {"status" "deleted" "name" name})))))

(defn handler-tools [req]
  (let [name (http/path-param req :name)]
    (if (nil? (db/get-mcp-server name))
      (http/not-found "mcp server not found")
      (http/ok {"server" name
                "tools" []
                "connected" false
                "note" "no MCP client is implemented in this build"}))))

(defn handler-call [req]
  (let [name (http/path-param req :name)]
    (if (nil? (db/get-mcp-server name))
      (http/not-found "mcp server not found")
      (http/not-implemented "mcp tool call"))))

(defn handler-login [req]
  (if (nil? (db/get-mcp-server (http/path-param req :id)))
    (http/not-found "mcp server not found")
    (http/not-implemented "mcp oauth login")))

(defn handler-oauth-callback [_]
  (http/not-implemented "mcp oauth callback"))

(def routes
  [["/api/mcp/servers" {:get handler-list :post handler-save}]
   ["/api/mcp/servers/:name" {:get (fn [req]
                                     (if-let [row (db/get-mcp-server (http/path-param req :name))]
                                       (http/ok (server->api row))
                                       (http/not-found "mcp server not found")))
                              :post handler-save
                              :put handler-save
                              :delete handler-delete}]
   ["/api/mcp/servers/:name/tools" {:get handler-tools}]
   ["/api/mcp/servers/:name/call" {:post handler-call}]
   ["/mcp/login/:id" {:get handler-login :post handler-login}]
   ["/mcp/oauth/callback" {:get handler-oauth-callback}]])
