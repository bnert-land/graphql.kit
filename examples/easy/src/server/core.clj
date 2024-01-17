(ns server.core
  (:require
    [examples.common.lacinia-engine.star-wars.resolvers :as ex.resolvers]
    [examples.common.core :as ex.core]
    [graphql.kit :as kit]
    [manifold.executor :as m.e]))

(def port 9112)

(defn -main [& _args]
  (kit/service!
    {:graphiql {:enabled?        true
                :url             "http://localhost:9112/graphql"
                :subscriptionUrl "ws://localhost:9112/graphql/subscribe"}
     :endpoints {:graphiql  "/graphiql"
                 :http      "/graphql"
                 :websocket "/graphql/subscribe"}
     :scalars   ex.core/scalars
     :resolvers {:query
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
     :server    {:port port}
     :schema    {:resource "graphql/schema/star-wars.edn"}
     :options   {:executor (m.e/execute-pool)}}))

