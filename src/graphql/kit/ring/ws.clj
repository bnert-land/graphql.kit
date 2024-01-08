(ns graphql.kit.ring.ws
  (:require
    [graphql.kit.connection-manager :as cm]
    [graphql.kit.runtime :as rt]
    [ring.websocket :as ws]))

(defn handle [req]
  ; this is merely a proto/facade on the underlying connection type
  ; therefore, we may be able to write some adaptation fns...
  {::ws/listener
   {:on-open
    (fn [socket]
      (cm/add rt/*connection-manager* req socket))
    :on-message
    (fn [socket msg]
      (cm/process rt/*connection-manager* req socket msg))
    :on-pong
    (fn [socket buffer]
      (cm/pong rt/*connection-manager* socket))
    :on-error
    (fn [socket throwable]
      (cm/error rt/*connection-manager* req socket throwable))
    :on-close
    (fn [socket code reason]
      (cm/remove rt/*connection-manager* socket req code reason))
    :on-ping
    (fn [socket buffer]
      (cm/ping rt/*connection-manager* req socket))}})

(defn handler [{:keys [resolvers scalars schema]
                :or   {resolvers          {}
                       scalars            {}}}]
  ; Also need to consider sub-protocols
  ; given they inform how a
  (fn graphql-ws-handler
    ([req]
     (assert (ws/websocket-request? req))
     (handle req))
    ([req res raise]
     (assert (ws/websocket-request? req))
     (res (handle res)))))

