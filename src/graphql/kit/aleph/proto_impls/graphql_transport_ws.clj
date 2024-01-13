; what a mouthful...
(ns graphql.kit.aleph.proto-impls.graphql-transport-ws
  (:refer-clojure :exclude [next])
  (:require
    [aleph.http :as http]
    [clojure.core.match :refer [match]]
    [graphql.kit.encdec :refer [encode decode]]
    [graohql.kit.engine :as e]
    [manifold.deferred :refer [chain' on-closed]]
    [manifold.stream :refer [consume put!]]))

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

(defn close! [conn reason-id & args]
  (let [[status m] (get close-lut reason-id close-default)]
    (cond
      (fn? m)
        (http/websocket-close conn status (m args))
      :else
        (http/websocket-close conn status m))))


(defn ack [{:keys [conn]} {:keys [payload]} state]
  (chain'
    (put! conn (encode {:type "connection_ack"}))
    #(when %
       (swap! state assoc :params payload :state :ready))))

(defn subscription-streamer [{:keys [conn]} {:keys [id]} _state]
  (fn subscription-streamer' [data]
    (put! conn (encode {:id id, :payload data, :type "next"}))))

(defn execute-subscription
  [{:keys [engine request schema] :as ctx}
   {:keys [id payload] :as msg}
   state]
  ; if throws, will propogate up to execute-operation, which will
  ; evict the reserved subscription
  ;
  ; If not thrown, will return a closer fn for when the client
  ; signal a subscription completion.
  (->> (e/subscribe engine
         {:ctx       {:graphql.kit/request request
                      :graphql.kit/params  (:params @state)}
          :payload   payload ; better kw name?
          :stream-fn (subscription-streamer ctx msg state)
          :schema    schema})
       (swap! state assoc-in [:subs id])))

(defn execute-query [{:keys [conn engine request schema]}
                     {:keys [id payload]}
                     state]
  (let [result (e/query engine
                 {:ctx     {:graphql.kit/request request
                            :graphql.kit/params  (:params @state)}
                  :payload payload
                  :schema  schema})]
    (put! conn (encode {:id id, :payload result, :type "next"}))
    (put! conn (encode {:id id, :type "complete"}))
    (swap! state update :subs dissoc id)))

(defn execute-operation
  [{:keys [conn engine schema] :as ctx}
   {:keys [id payload] :as msg}
   state]
  (cond
    (not= :ready (:status @state))
      (close! conn :not-ready)
    (contains? (:subs @state) id)
      (close! conn :extant-subscriber id)
    :else
      (try
        ; reserve id in subscription w/ a noop
        ; doing this eagerly to avoid waiting until query is parsed
        ; in order to finally resolve.
        ;
        ; Little more complext but less of a concurrency concern for
        ; clients
        (swap! state assoc-in [:subs id] #())
        (let [parsed (e/parse engine ctx)
              msg    {:id id, :payload (assoc payload :query parsed)}]
          (if (= :subscription (e/op-kind engine parsed))
            (execute-subscription ctx msg state)
            (execute-query ctx msg state)))
        (catch Exception _e
          ; TODO: log... maybe close?
          (swap! state update :subs dissoc id)))))

(defn complete
  [_ctx {:keys [id]} state]
  (when-let [completer (get-in @state [:subs id])]
    (completer)
    (swap! state update :subs dissoc :id)))

(defn process* [ctx msg state]
  (match [(:status @state) (:type msg)]
    [:init "connection_init"]
      (ack ctx msg state)
    [:ready "connection_init"]
      (close! ctx :redundant-init)
    [:ready "subscribe"]
      (execute-operation ctx msg state)
    [:ready "complete"]
      (complete ctx msg state)
    :else
      (close! ctx :invalid-message)))

(defn process [{:keys [conn] :as ctx}]
  (let [state (atom {:status :init, :subscriptions {}, :params nil})]
    (consume
      #(if-let [msg (decode %)]
        (process* ctx msg state)
        (close! ctx :invalid-msg))
      conn)
    (on-closed conn
      (fn []
        (when (= :ready (:status @state))
          (swap! state assoc :state :closed :params nil)
          (doseq [closer (vals (:subs @state))]
            (try
              (when (fn? closer)
                (closer))
              (catch Exception _
                nil))))))))

