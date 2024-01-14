(ns server.core
  (:require
    [aleph.http :as http]
    [taoensso.timbre :refer [info]]
    [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
    [clojure.core.match :refer [match]]
    [graphql.kit.examples.common.core :as ex.core]
    [graphql.kit.engines.lacinia :as kit.engine]
    [graphql.kit.loaders.edn :as kit.loader]
    [graphql.kit.servers.aleph.ws :as kit.ws]
    [graphql.kit.servers.ring.http :as kit.http]
    [graphql.kit.servers.ring.graphiql :as kit.graphiql]
    [jsonista.core :as json]
    [manifold.bus :as m.b]
    [manifold.executor :as m.e]
    [manifold.stream :as m.s]
    [ring.middleware.params :as mw.params]))

(def port 9109)

(def bus (m.b/event-bus))

(def db (ex.core/heros-db!))

(defn tag [x]
  (case (:kind x)
    :droid    (tag-with-type x :Droid)
    :human    (tag-with-type x :Human)
    #_default x))

; -- Query Resolvers

(defn droid [_ctx {:keys [id]} _value]
  (get-in @db [:droid id]))

(defn droids [_ctx {:keys [episode]} _value]
  (cond->> (-> @db :droid vals)
    episode
       (filter #(contains? (:appearsIn %) episode))
    true
       (sort-by :name)))

(defn hero [_ctx {:keys [id]} _value]
  (tag
    (or (get-in @db [:human id])
        (get-in @db [:droid id]))))

(defn heros [_ctx {:keys [episode]} _value]
  (cond->> (concat (vals (:human @db))
                   (vals (:droid @db)))
    episode
      (filter #(contains? (:appearsIn %) episode))
    true
      (sort-by :name)
    true
      (map tag)))

(defn human [_ctx {:keys [id]} _value]
  (get-in @db [:human id]))

(defn humans [_ctx {:keys [episode]} _value]
  (cond->> (-> @db :human vals)
    episode
      (filter #(contains? (:appearsIn %) episode))
    true
      (sort-by :name)))


; -- Mutation resolvers

(defn add-droid [_ctx args _value]
  (let [args'  (assoc args :kind :droid)
        result (tag (ex.core/add-hero db args'))]
    (m.b/publish! bus :events
      {:kind :ENTITY_ADDED
       :data result})
    result))


(defn add-human [_ctx args _value]
  (let [args'  (assoc args :kind :human)
        result (tag (ex.core/add-hero db args'))]
    (m.b/publish! bus :events
      {:kind :ENTITY_ADDED
       :data (tag result)})
    result))


; -- Subscription Resolvers

(defn events [_ctx _args ->stream]
  (let [events (m.b/subscribe bus :events)]
    (info "Subscribing to events")
    (m.s/consume ->stream events)
    #(do
       (info "Closing subscription")
       (m.s/close! events))))

; --

(def kit-config
  {:graphql.kit/engine (kit.engine/engine!)
   :graphql.kit/loader (kit.loader/loader!)
   :scalars ex.core/scalars
   :schema
   {:resource "graphql/schema/star-wars.edn"}
   :resolvers
   {:query
    {:Mutation/addDroid add-droid
     :Mutation/addHuman add-human
     :Query/droid  droid
     :Query/droids droids
     :Query/human  human
     :Query/humans humans
     :Query/hero   hero
     :Query/heros  heros}

    :subscription
    {:Subscription/events events}}
   :options
   {:executor (m.e/execute-pool)}})

; -- Handlers

(def http-handler
  (kit.http/handler kit-config))

(def ws-handler
  (kit.ws/handler kit-config))

(def graphiql-handler
  (kit.graphiql/handler
    {:enabled?        true
     :url             "http://localhost:9109/graphql"
     :subscriptionUrl "ws://localhost:9109/graphql/subscribe"}))

; -- middlware

(defn wrap-json [handler]
  (fn [req]
    (let [b   (json/read-value (:body req) json/keyword-keys-object-mapper)
          res (handler (update req :params (fnil into {}) b))]
      (if (string? (:body res))
        res
        (update res :body json/write-value-as-string)))))

(defn server! []
  (http/start-server
    (-> (fn [{:keys [request-method uri] :as req}]
          (match [request-method uri]
            [:get "/graphql"]
              (http-handler req)
            [:post "/graphql"]
              (http-handler req)
            [:get "/graphql/subscribe"]
              (ws-handler req)
            [:get "/graphiql"]
              (graphiql-handler req)
            :else
              {:status 404, :body nil}))
        (mw.params/wrap-params)
        (wrap-json))
    {:port port}))

(defn -main [& _args]
  (info (str "Serving on http://localhost:" port))
  (server!))

