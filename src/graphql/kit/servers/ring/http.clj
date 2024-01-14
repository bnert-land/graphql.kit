(ns graphql.kit.servers.ring.http
  (:require
    [graphql.kit.protos.engine :as e]
    [graphql.kit.util :as util]))

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

(defn- handle [{:keys [engine request schema]}]
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
     ; TODO: better error messages for missing query
     :body    {:errors [{}]}}))

(def handler
  (util/handle->ring-handler handle))

