(ns graphql.kit.ring.http
  (:require
    [graphql.kit.engine :as engine]
    [graphql.kit.runtime :as rt]
    [graphql.kit.util :refer [load+compile]]))

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
  (let [schema' (load+compile schema)]
    (fn graphql-http-handler
      ; sync
      ([req]
       (query req schema'))
      ([req res raise]
       (res
         (query req schema'))))))


