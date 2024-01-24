(ns graphql.kit.services.aleph
  (:require
    [aleph.http :as aleph]
    [graphql.kit.engines.lacinia :as kit.engine]
    [graphql.kit.loaders.aero :as kit.l.aero]
    [graphql.kit.loaders.default :as kit.l.default]
    [graphql.kit.servers.aleph.ws :as kit.ws]
    [graphql.kit.servers.ring.http :as kit.http]
    [graphql.kit.servers.ring.graphiql :as kit.graphiql]
    [muuntaja.core :as m.core]
    [muuntaja.middleware :as m.mw]
    [taoensso.timbre :refer [info]]))

(defn kit-config! [loader]
  {:graphql.kit/engine (kit.engine/engine!)
   :graphql.kit/loader loader})

(defn handler? [handler-lut req]
  (get-in handler-lut [(:request-method req) (:uri req)]))

(defn extension? [^String s]
  (let [i (.lastIndexOf s ".")]
    (if (< i 0)
      nil
      (subs s i))))

(defn appropos-loader [{:keys [schema]}]
  (let [filename (or (:resource schema) (:file schema) schema)]
    (if-not (string? filename)
      (throw (ex-info "failed to determine schema kind"
                      {:schema schema}))
      (if (= ".edn" (extension? filename))
        (kit.l.aero/loader!)
        (kit.l.default/loader!)))))


(defn service-handler  [handler-lut]
  (fn service-handler*
    ([req]
     (if-let [h? (handler? handler-lut req)]
       (h? req)
       {:status 404}))
    ([req res raise]
     (if-let [h? (handler? handler-lut req)]
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

        loader      (appropos-loader opts')
        kit-config  (kit-config! loader)
        ; --
        http* (kit.http/handler (into kit-config opts'))
        ws*   (kit.ws/handler (into kit-config opts'))
        ide*  (kit.graphiql/handler (get opts :graphiql {}))
        handler-lut (cond-> {}
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
        root-handler (reduce #(%2 %1) (service-handler handler-lut) chain)
        ; --
        server-opts (into {:port 9109} (:server opts))]
    ; TODO: better log message
    (info "Serving graphql.kit on:" (-> server-opts :port))
    (aleph/start-server
      (cond-> root-handler
        (:async? server-opts)
          aleph/wrap-ring-async-handler)
      server-opts)))

