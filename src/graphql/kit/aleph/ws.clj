(ns graphql.kit.aleph.ws
  (:require
    [aleph.http :as http]
    [aleph.http.websocket.common :as ws.common]
    [aleph.http.websocket.server :as ws.server]
    [graphql.kit.util :refer [load+compile]]))
    [manifold.deferred :as d]
    [manifold.stream :as m.s]
    [manifold.stream.default :as stream.default]
    [ring.websocket.protocols :as wsp]))

; this seems a bit too hacky, given it is reliant on
; a hidden/obscure api...
; (extend-type stream.default/Stream
;   wsp/Socket
;   (-open? [this]
;     (not (m.s/closed? this)))
;   (-send [this message]
;     @(m.s/put! this message)))

(defn reify-stream [conn-stream]
  (reify
    wsp/Socket
    (-open? [_]
      true)
    (-send [_ message]
      @(m.s/put! conn-stream message)
      nil)
    (-ping [_ data]
      (let [d* (d/deferred)]
        (http/websocket-ping conn-stream d* data)))
    (-pong [_ _]
      #_"already handled internally")
    (-close [_ status reason]
      (let [d* (d/deferred)]
        (ws.common/websocket-close! conn-stream status reason d*)))
    wsp/AsyncSocket
    (-send-async [_ message succeed fail]
      (-> (put! conn-stream message)
          (d/chain succeed)
          (d/catch fail)))))

(defn decode [msg]
  (json/read-value msg json/keyword-keys-object-mapper))

(defn encode [msg]
  (json/write-value-as-bytes msg))

(defn processor [out-stream]
  (let [s     (m.s/stream)
        state (atom {:subscriptions {}
                     :state         nil})

    (m.s/consume 
      (fn [msg]
        (case (:type msg)
          ; client -> server
          "connection_init"
            (if (:state @state)
              (too-many-inits out-stream)
              (do
                (send-ack out-stream)
                (swap! state assoc :state :initiailzed)))
          #_#_"connection_ack" ""
          "ping"           "pong"
          "pong"           "ping"
          ; client -> server
          "subscribe"
            (let [{:keys [id payload]} msg]
              (zhu-li-do-the-thing out-stream id payload))
          ; server -> client
          #_#_"next"
            (let [{:keys [id payload]} msg]
              (handle-next-payload out-stream id payload))
          #_#_"error"
            (let [{:keys [id payload]}]
              (handle-error out-stream id payload))
          ; server -> client: operation completed
          ; client -> server: stopped listening, close subscription
          ;                   should also prevent one-off from
          ;                   propogating to client
          "complete"
            (let [{:keys [id]}]
              (when-let [s (get-in @state [:subs id])]
                (m.s/close! s)
                (swap! state update :subscriptions dissoc id))))))))

(defn handle [req]
  (assert (ws/websocket-upgrade-request? req) "")
  (d/let-flow [c                 (http/websocket-connection req)
               decode            (s/stream* {:xform (map decode)})
               encode            (s/stream* {:xform (map encode)})

               client->processor ()
               processor->encode ()
               processor         ()
               encode->client    ()]
    ; 1. Setup "processing chain" for
    ; client message to decide wether a subscription
    ; or a query/mutation and to act accordingly
    ; will require parsing/preparing the query in order to
    ; examine the top level fields
    ;
    ; In the processing chain setup, a function needs to be constructed
    ; which provides the ability to pass resolved results back to an
    ; encoder and a means to provide a message back to the client.
    ;
    ; Also need to consider "one-off" queries/mutations with the websocket
    ; handler, given that is technically allowed via the spec, though
    ; query parsing + preparing and examining the selections should allow
    ; for deciding if a query should be executed or a subscription logged.

    ; Following won't totally work, given subscriptions require
    ; a stateful element...
    ; decode and encode can be decoupled and simple
    (m.s/connect c decode {:description "decodes message"})
    (m.s/connect decode execute-query {:description "executes query"})
    (m.s/connect execute-query encode {})
    (m.s/connect encode c {})
    nil))

(defn handler [{:keys [resolvers scalars schema]
                :or   {resolvers          {}
                       scalars            {}}}]
  (let [schema' (load+compile schema
                              {:resolvers resolvers
                               :scalars   scalars})]
    (fn graphql-ws-handler
      ([req]
       (handle req))
      ([req res raise]
       (res (handle req))))))
