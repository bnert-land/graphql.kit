(ns server.core
  (:require
    [examples.common.lacinia-engine.star-wars.resolvers :as ex.resolvers]
    [examples.common.core :as ex.core]
    [graphql.kit :as kit]
    [manifold.executor :as m.e]
    [taoensso.timbre :refer [info]]))

(def port 9113)

; TODO: Figure out better examples (i.e. auth(n,z), query complexity, db, CORS)
(defn logger [message]
  (fn [handler]
    (fn
      ([req]
       (info message "I'm a logger, fear my axe...")
       (handler req))
      ([req res raise]
       (info message "I'm a logger, fear my axe at some point in the future...")
       (handler req res raise)))))

(defn health-check [endpoint]
  (let [health? #(and (= :get (:request-method %))
                      (= endpoint (:uri %)))
        ok-resp {:status  200
                 :headers {"content-type" "text/plain"}
                 :body    "ok"}]
    (fn [handler]
      (fn
        ([req]
         (info "health sync")
         (if (health? req)
           ok-resp
           (handler req)))
        ([req res raise]
         (info "health async")
         (if (health? req)
           (res ok-resp)
           (handler req res raise)))))))

(defn -main [& _args]
  (kit/service!
    {:graphiql {:enabled?        true
                :url             "http://localhost:9113/graphql"
                :subscriptionUrl "ws://localhost:9113/graphql/subscribe"}
     :endpoints {:graphiql  "/graphiql"
                 :http      "/graphql"
                 :websocket "/graphql/subscribe"}
     ; Underlying, there is a default "middleware chain". The main responsibility
     ; is to parse query params and json, as well as format responses.
     ;
     ; The resulting middleware will look like
     ; logger -> parsing ->  logger -> health-check -> root-handler
     :middleware {:prepend [(logger "PREPEND>")]
                  :append  [(logger "APPEND>") (health-check "/health")]}
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
     :server    {:port port, #_#_:async? true}
     :schema    {:resource "graphql/schema/star-wars.graphql"}
     :options   {:executor (m.e/execute-pool)}}))

