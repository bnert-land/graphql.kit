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
    [manifold.executor :as m.e]
    [ring.middleware.keyword-params :as mw.keyword-params]
    [ring.middleware.params :as mw.params]
    [ring.middleware.json :as mw.json]
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
        (mw.json/wrap-json-response)
        (mw.keyword-params/wrap-keyword-params)
        (mw.params/wrap-params)
        (mw.json/wrap-json-params))
    {:port port}))

(defn -main [& _args]
  (info (str "Serving on http://localhost:" port))
  (server!))

