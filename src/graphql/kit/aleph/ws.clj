(ns graphql.kit.aleph.ws
  (:require
    [aleph.http :as http]
    [clojure.string :as str]
    [clojure.core.match :refer [match]]
    [graphql.kit.encdec :refer [encode decode]]
    [graphql.kit.engine :as engine]
    [graphql.kit.runtime :as rt]
    [graphql.kit.aleph.proto-impls.graphql-transport-ws :as gql-transport-ws]]
    [graphql.kit.util :refer [load+compile]]
    [jsonista.core :as json]
    [manifold.deferred :as m.d]
    [manifold.stream :as m.s]))

; -- general helpers

(defn protocols [req]
  (-> req
      :headers
      (get "sec-websocket-protocol" "")
      (str/split #"," 16) ; why would there be more than 16 protocols?
      (set)))

; --

(defn handle [req schema engine]
  ; TODO: handle non websocket request
  (let [protos  (protocols req)]
    (cond
      (contains? protos gql-transport-ws/protocol-id)
        (m.d/let-flow [c (http/websocket-connection req
                           {:headers
                            {"sec-websocket-protocol" "graphql-transport-ws"}})]
          (gql-transport-ws/process {:conn c, :request req, :schema schema, :engine engine}))
      :else
        (m.d/let-flow [c (http/websocket-connection req {})]
          (http/websocket-close! c 4400 "Invalid subprotocol"))))
  nil)

(defn handler [{:keys [resolvers scalars schema engine]
                :or   {resolvers          {}
                       scalars            {}}}]
  (let [schema' (load+compile schema
                              {:resolvers resolvers
                               :scalars   scalars})]
    (fn graphql-ws-handler
      ([req]
       (handle req schema' engine))
      ([req res raise]
       (res (handle req schema' engine))))))

