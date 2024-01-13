(ns graphql.kit.aleph.ws
  (:require
    [aleph.http :as http]
    [clojure.string :as str]
    [graphql.kit.aleph.proto-impls.graphql-transport-ws :as gql-transport-ws]
    [graphql.kit.engine :as e]
    [graphql.kit.util :refer [load-schema]]
    [manifold.deferred :refer [let-flow]]))

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
        (let-flow [c (http/websocket-connection req
                           {:headers
                            {"sec-websocket-protocol" "graphql-transport-ws"}})]
          (gql-transport-ws/process
            {:conn c, :request req, :schema schema, :engine engine}))
      :else
        (let-flow [c (http/websocket-connection req {})]
          (http/websocket-close! c 4400 "Invalid subprotocol"))))
  nil)

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
    (fn graphql-ws-handler
      ([req]
       (handle req schema' engine))
      ([req res raise]
       (res (handle req schema' engine))))))

