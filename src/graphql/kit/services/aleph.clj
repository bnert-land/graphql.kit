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

; delay is for AoT
(def kit-config
  (delay
    {:graphql.kit/engine (kit.engine/engine!)
     :graphql.kit/loader (kit.loader/loader!)}))

(defn handler? [lut req]
  (get-in lut [(:request-method req) (:uri req)]))

(defn service-handler  [lut]
  (fn service-handler
    ([req]
     (if-let [h? (handler? lut req)]
       (h? req)
       {:status 404}))
    ([req res raise]
     (if-let [h? (handler? lut req)]
       (h? req res raise)
       (res {:status 404})))))

(defn service! [opts]
  (let [opts' (dissoc opts :endpoints :graphiql :middleware :server)
        {:keys [graphiql http websockets]
         :or   {graphiql   nil #_"/graphiql"
                http       "/graphql"
                websockets "/graphql/subscribe"}} (:endpoints opts)
        {:keys [append prepend]
         :or   {append  []
                prepend []}} (:middleware opts)

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
                ; [inner ... outer]
                ; [append ... prepend]
        chain   (as-> [m.mw/wrap-params #(m.mw/wrap-format % m.core/instance)] $
                 ; need to reverse, here, otherwise append middleware will
                 ; be applied right to left, not left to right.
                 (into (vec (reverse append)) $)
                 (into $ prepend))
        ; compiles all the middleware into a single chain
        root-handler (reduce #(%2 %1) (service-handler lut) chain)
        ; --
        server-opts (into {:port 9109} (:server opts))]
    ; TODO: better log message
    (info "Serving graphql.kit on:" (-> server-opts :port))
    (aleph/start-server
      (cond-> root-handler
        (:async? server-opts)
          aleph/wrap-ring-async-handler)
      server-opts)))

