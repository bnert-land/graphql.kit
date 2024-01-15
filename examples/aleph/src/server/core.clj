(ns server.core
  (:require
    [aleph.http :as http]
    [clojure.core.match :refer [match]]
    [examples.common.lacinia-engine.star-wars.resolvers :as ex.resolvers]
    [examples.common.core :as ex.core]
    [graphql.kit.engines.lacinia :as kit.engine]
    [graphql.kit.loaders.edn :as kit.loader]
    [graphql.kit.servers.aleph.ws :as kit.ws]
    [graphql.kit.servers.ring.http :as kit.http]
    [graphql.kit.servers.ring.graphiql :as kit.graphiql]
    [jsonista.core :as json]
    [manifold.executor :as m.e]
    [ring.middleware.params :as mw.params]
    [taoensso.timbre :refer [info]]))

(def port 9109)

; --

(def kit-config
  {:graphql.kit/engine (kit.engine/engine!)
   :graphql.kit/loader (kit.loader/loader!)
   :scalars ex.core/scalars
   :schema
   {:resource "graphql/schema/star-wars.edn"}
   :resolvers
   {:query
    {:Mutation/addDroid ex.resolvers/add-droid
     :Mutation/addHuman ex.resolvers/add-human
     :Query/droid       ex.resolvers/droid
     :Query/droids      ex.resolvers/droids
     :Query/human       ex.resolvers/human
     :Query/humans      ex.resolvers/humans
     :Query/hero        ex.resolvers/hero
     :Query/heros       ex.resolvers/heros}
    :subscription
    {:Subscription/events ex.resolvers/events}}
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

