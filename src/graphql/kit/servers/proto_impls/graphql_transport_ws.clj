; Copyright (c) Brent Soles. All rights reserved.
;
; what a mouthful...
;
; I fought it at first, but having manifold
; be the "bedrock" instead of trying to shoehorn
; in extra context for operation makes having
; a unified approach a little easier.
;
; In my mind, until aleph (others...?) implement
; the ring protocol for websockets, I'd rather
; deal w/ manifold as the target layer, rather than
; the ring spec, specifically due to
; the fact the ring spec relies heavily on the web stack
; interfaces/idioms, which complicates app code from
; the brief experiment I did.
(ns graphql.kit.servers.proto-impls.graphql-transport-ws
  (:refer-clojure :exclude [next])
  (:require
    [graphql.kit.encdec :refer [encode decode]]
    [graphql.kit.protos.engine :as kit.e]
    [manifold.deferred :refer [chain']]
    [manifold.stream :refer [buffer consume put! on-closed]]))

(def protocol-id "graphql-transport-ws")

(def close-lut
  {:not-ready
     [4400 "Connection not ready"]
   :extant-subscriber
     [4409 #(str "Subscriber for " % " already exists")]
    :redundant-init
     [4429 "Too many initialisation requests"]
    :invalid-msg
     [4400 "Invalid message"]})

; poor default, but good enough for right now...
(def close-default (:invalid-msg close-lut))

(defn closer! [closer conn]
  (fn closer' [reason-id & args]
    (let [[status m] (get close-lut reason-id close-default)]
      (cond
        (fn? m)
          (closer conn status (m args))
        :else
          (closer conn status m)))))

(defn ack [{:keys [conn]} {:keys [payload]} state]
  (chain'
    (put! conn (encode {:type "connection_ack"}))
    #(when %
       (swap! state assoc :params payload :status :ready))))

(defn ping [{:keys [conn]} {:keys [id]} _]
  (put! conn (encode {:id id, :type "pong"})))

(defn pong [{:keys [conn]} {:keys [id]} _]
  (put! conn (encode {:id id, :type "ping"})))

(defn subscription-streamer [{:keys [conn]} {:keys [id]} _state]
  (fn subscription-streamer' [data]
    (if (:errors data)
      (put! conn (encode  {:id      id
                           :payload (:errors data)
                           :type    "error"}))
      (put! conn (encode {:id      id
                          :payload data
                          :type    "next"})))))

(defn execute-subscription
  [{:keys [engine request schema] :as ctx}
   {:keys [id payload] :as msg}
   state]
  ; if throws, will propogate up to execute-operation, which will
  ; evict the reserved subscription
  ;
  ; If not thrown, will return a closer fn for when the client
  ; signal a subscription completion.
  (->> (kit.e/subscribe engine
         {:ctx       {:graphql.kit/request request
                      :graphql.kit/params  (:params @state)}
          :payload   payload ; better kw name?
          :stream-fn (subscription-streamer ctx msg state)
          :schema    schema})
       (swap! state assoc-in [:subs id])))

(defn execute-query [{:keys [conn engine request schema]}
                     {:keys [id payload]}
                     state]
  (let [result (kit.e/query engine
                 {:ctx     {:graphql.kit/request request
                            :graphql.kit/params  (:params @state)}
                  :payload payload
                  :schema  schema})]
    (if (:errors result)
      (put! conn (encode {:id      id
                          :payload (:errors result)
                          :type    "error"}))
      (do
        (put! conn (encode {:id id, :payload result, :type "next"}))
        (put! conn (encode {:id id, :type "complete"}))))
    (swap! state update :subs dissoc id)))

(defn execute-operation
  [{:keys [close! engine schema] :as ctx}
   {:keys [id payload]}
   state]
  (cond
    (not= :ready (:status @state))
      (close! :not-ready)
    (contains? (:subs @state) id)
      (close! :extant-subscriber id)
    :else
      (try
        ; reserve id in subscription w/ a noop
        ; doing this eagerly to avoid waiting until query is parsed
        ; in order to finally resolve.
        ;
        ; Little more complex but less of a concurrency concern for
        ; client
        (swap! state assoc-in [:subs id] #())
        (let [parsed (kit.e/parse engine
                                  {:schema schema, :payload payload})
              msg    {:id id, :payload (assoc payload :query parsed)}]
          (if (= :subscription
                 (kit.e/op-kind engine
                                {:schema  schema
                                 :payload (:payload msg)}))
            (execute-subscription ctx msg state)
            (execute-query ctx msg state)))
        (catch Exception _e
          (swap! state update :subs dissoc id)))))

(defn complete
  [_ctx {:keys [id]} state]
  (when-let [completer (get-in @state [:subs id])]
    (completer)
    (swap! state update :subs dissoc :id)))

(defn state! []
  (atom {:status :init, :subscriptions {}, :params nil}))

(defn close-with [id]
  (fn [{:keys [close!]} _ _]
    (close! id)))

; --

(def lut*
  {:init  {"connection_init"  ack}
   :ready {"complete"        complete
           "connection_init" (close-with :redundant-init)
           "ping"            ping
           "pong"            pong
           "subscribe"       execute-operation}})

(defn process* [{:keys [close!] :as ctx} msg state]
  (if-let [h? (get-in lut* [(:status @state) (:type msg)])]
    (h? ctx msg state)
    (close! :invalid-message)))

(defn processor [{:keys [close! conn] :as ctx}]
  (let [state (state!)]
    (consume
      #(if-let [msg (decode %)]
        (process* ctx msg state)
        (close! :invalid-msg))
      ; buffers the "source", in order to have some back pressure.
      ; TODO: make configurable via context
      (buffer 64 conn)) ; let's be computer-y. Is 64 too much?
    ; take our of processor
    (on-closed conn
      (fn []
        (when (= :ready (:status @state))
          (swap! state assoc :status :closed :params nil)
          (doseq [closer (vals (:subs @state))]
            (try
              (when (fn? closer)
                (closer))
              (catch Exception _
                nil))))))))

