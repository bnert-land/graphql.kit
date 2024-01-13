(ns graphql.kit.ring.http
  (:require
    [graphql.kit.engine :as e]
    [graphql.kit.util :refer [load-schema]]))

(defn- find-query [req]
  ; Better way?
  (loop [ks [:params :form-params :query-params]]
    (cond
      (not (seq ks))
        nil
      (seq (get req (first ks)))
        (get req (first ks))
      :else
        (recur (rest ks)))))

(defn- query [{:keys [engine request schema]}]
  (if-let [query (find-query request)]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (e/query engine
                {:ctx     {:graphql.kit/request request
                           :graphql.kit/params  {}}
                 :payload query
                 :schema  schema})}
    {:status 400
     :headers {"Content-Type" "application/json"}
     ; TODO: better error messages
     :body    {:errors [{}]}}))


; --

(defn handler [{:graphql.kit/keys [engine loader]
                :keys             [options resolvers scalars schema]
                :or               {schema    {:resource "graphql.kit/schema.edn"}
                                   resolvers {}
                                   scalars   {}}}]
  (let [loaded  (load-schema loader schema)
        schema' (e/compile engine
                           {:options   options
                            :resolvers resolvers
                            :scalars   scalars
                            :schema    loaded})]
    (fn graphql-http-handler
      ([req]
       (query {:engine engine, :request req, :schema schema'}))
      ([req res raise]
       (res
         (query {:engine engine, :request req, :schema schema'}))))))


