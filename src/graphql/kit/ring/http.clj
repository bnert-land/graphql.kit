(ns graphql.kit.ring.http
  (:require
    [graphql.kit.compiler :as compiler]
    [graphql.kit.engine :as engine]
    [graphql.kit.loader :as loader]
    [graphql.kit.runtime :as rt]))

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

(defn- load-schema [schema]
  (cond
    (and (map? schema) (:path schema))
      (loader/path rt/*loader* (:path schema))
    (and (map? schema) (:resource schema))
      (loader/resource rt/*loader* (:resource schema))
    (map? schema)
      schema
    :else
      ; assume :schema is a string and resource path
      (loader/resource rt/*loader* schema)))

(defn- query [req schema]
  (if-let [query (find-query req)]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body
     (engine/query
       rt/*engine*
       (into
         (select-keys query [:query :variables])
         {:schema schema
          :ctx    {:request req}
          :opts   {:operation-name (:operationName query)}}))}
    {:status 400
     :headers {"Content-Type" "application/json"}
     ; TODO: better error messages
     :body    {:errors [{}]}}))


; --

(defn handler [{:keys [resolvers scalars schema]
                :or   {resolvers {}
                       scalars   {}}}]
  (let [schema' (compiler/compile rt/*engine*
                                  (load-schema schema)
                                  {:scalars   scalars
                                   :resolvers resolvers})]
    (fn graphql-handler
      ; sync
      ([req]
       (query req schema'))
      ([req res raise]
       (res
         (query req schema'))))))

