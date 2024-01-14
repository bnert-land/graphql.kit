; Copyright (c) Brent Soles. All rights reserved.
(ns graphql.kit.servers.aleph.ws
  (:require
    [aleph.http :as http]
    [graphql.kit.protos.engine :as kit.e]
    [graphql.kit.util :as util]
    [graphql.kit.servers.proto-impls.graphql-transport-ws :as gtw]
    [manifold.deferred :refer [let-flow]]))

(defn handle [{:keys [request] :as ctx}]
  ; TODO: handle non websocket request
  (let [protos  (util/protocols request)]
    (cond
      (contains? protos gtw/protocol-id)
        (let-flow [h {:headers
                      {"sec-websocket-protocol" "graphql-transport-ws"}}
                   c (http/websocket-connection request h)]
          (gtw/processor
            (assoc ctx
                   :close! (gtw/closer! http/websocket-close! c)
                   :conn   c)))
      ; --
      :else
        (let-flow [c (http/websocket-connection request {})]
          (http/websocket-close! c 4400 "Invalid subprotocol"))))
  nil)

(def handler
  (util/handle->ring-handler handle))

