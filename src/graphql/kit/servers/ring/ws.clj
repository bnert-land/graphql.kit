; Copyright (c) Brent Soles. All rights reserved.
(ns graphql.kit.servers.ring.ws
  (:require
    [graphql.kit.protos.engine :as kit.e]
    [graphql.kit.util :as util]
    [graphql.kit.servers.proto-impls.graphql-transport-ws :as gtw]
    [manifold.stream :as s]
    [ring.websocket :as ws]
    [ring.websocket.protocols :as wsp]
    [taoensso.timbre :refer [debug error]]))

(defn noop [])

(defn handle [ctx]
  (let [protos (util/protocols (:request ctx))
        state  (atom {:status :init, :subs {}, :params nil})]
    (cond
      (contains? protos gtw/protocol-id)
        (let [in     (s/stream)
              out    (s/stream)
              stream (s/splice out in)]
          {::ws/protocol gtw/protocol-id
           ::ws/listener
           {:on-open
            (fn [socket]
              (gtw/processor
                (assoc ctx
                       :close! (gtw/closer! ws/close socket)
                       :conn   stream))
              ; i.e. result -> socket -> client
              (s/consume
                #(if (and (:async? ctx)
                          (satisfies? wsp/AsyncSocket socket))
                  (ws/send socket % noop (fn [e] (error e)))
                  (ws/send socket %))
                out)
              (s/on-closed stream
                #(ws/close socket)))
            ; --
            :on-message
            (fn [_socket msg]
              ; i.e. socket -> in-s
              (s/put! in msg))
            #_#_:on-error
            (fn [_socket throwable]
              (println "T" throwable)
              (error throwable))
            :on-close
            (fn [_socket code reason]
              (debug "Socket closed" code reason)
              ; info args?
              (when (= :ready (:status @state))
                (s/close! stream)
                (swap! state assoc :status :closed)
                (doseq [closer (vals (:subs @state))]
                  (closer))))}})
      :else
        {:status 400})))

(def handler
  (util/handle->ring-handler handle))

