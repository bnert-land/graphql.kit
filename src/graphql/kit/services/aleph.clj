(ns graphql.kit.services.aleph
  (:require
    [aleph.http :as aleph]
    [graphql.kit.engines.lacinia :as kit.engine]
    [graphql.kit.loaders.aero :as kit.loader]
    [graphql.kit.servers.aleph.ws :as kit.ws]
    [graphql.kit.servers.ring.http :as kit.http]
    [graphql.kit.servers.ring.graphiql :as kit.graphiql]
    [muuntaja.core :as m.core]
    [muuntaja.middleware :as m.mw]
    [taoensso.timbre :refer [info]]))

; for AoT
(def kit-config
  (delay
    {:graphql.kit/engine (kit.engine/engine!)
     :graphql.kit/loader (kit.loader/loader!)}))

(defn service! [opts]
  (let [opts' (dissoc opts :server :endpoints :graphiql)
        {:keys [graphiql http websockets]
         :or   {graphiql   nil #_"/graphiql"
                http       "/graphql"
                websockets "/graphql/subscribe"}} (:endpoints opts)
        ; --
        http* (kit.http/handler (into @kit-config opts'))
        ws*   (kit.ws/handler (into @kit-config opts'))
        ide*  (kit.graphiql/handler (get opts :graphiql {}))
        lut   (cond-> {}
                http       (assoc-in [:get http] http*)
                http       (assoc-in [:post http] http*)
                websockets (assoc-in [:get websockets] ws*)
                graphiql   (assoc-in [:get graphiql] ide*))
        ; --
        server-opts (into {:port 9109} (:server opts))]
    ; TODO: better log message
    (info "Serving graphql.kit on:" (-> server-opts :port))
    (aleph/start-server
      (-> (fn service-handler
            ([req]
             (if-let [h? (get-in lut
                                 [(:request-method req)
                                  (:uri req)])]
               (h? req)
               {:status 404}))
            ([req res _raise]
             (res
               (if-let [h? (get-in lut
                                   [(:request-method req)
                                    (:uri req)])]
                 (h? req)
                 {:status 404}))))
          (m.mw/wrap-params)
          (m.mw/wrap-format m.core/instance))
      server-opts)))

