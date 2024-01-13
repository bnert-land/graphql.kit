(ns graphql.kit.ring.proto-impls.graphql-transport-ws
  (:require
    [clojure.core.match :refer [match]]
    [graphql.kit.encdec :refer [encode decode]]
    [graphql.kit.engine :as e]
    [ring.websocket :as ws]))

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

(def close-default (:invalid-msg close-lut))

(defn close! [conn reason-id & args]
  (let [[status m] (get close-lut reason-id close-default)]
    (cond
      (fn? m)
        (ws/close conn status (m args))
      :else
        (ws/close conn status m))))

(defn ack [{:keys [conn]} _msg state]
  (ws/send conn (encode {:type "connection_ack"})
    (fn ok []
      (swap! state assoc :status :ready))
    (fn err [_e]
      ; log error?
      (close! conn :invalid-msg))))

(defn ping [{:keys [conn]} {:keys [id]} _state]
  (ws/send conn (encode {:id id, :type "pong"})
    (fn ok []
      ; noop
      )
    (fn err [_e]
      ;log
      )))

(defn pong [{:keys [conn]} {:keys [id]} _state]
  (ws/send conn (encode {:id id, :type "ping"})
    (fn ok []
      ; noop
      )
    (fn err [_e]
      ;log
      )))

(defn execute-query [{:keys [conn engine request schema]}
                     {:keys [id payload]}
                     state]
  (let [result (e/query engine
                 {:ctx     {:graphql.kit/request request
                            :graphql.kit/params  (:params @state)}
                  :payload payload
                  :schema  schema})]
    (if (:errors result)
      (ws/send conn (encode {:id      id
                             :payload (:errors result)
                             :type    "error"}))
      ; should these be async?
      (do
        (ws/send conn (encode {:id id, :payload result, :type "next"}))
        (ws/send conn (encode {:id id, :type "complete"}))))
    (swap! state update :subs dissoc id)))

(defn subscription-streamer [{:keys [conn]} {:keys [id]} _state]
  (fn subscription-streamer' [data]
    (if (:errors data)
      (ws/send conn (encode {:id      id
                             :payload (:errors data)
                             :type    "error"}))
      (ws/send conn (encode {:id      id
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
  (->> (e/subscribe engine
         {:ctx       {:graphql.kit/request request
                      :graphql.kit/params  (:params @state)}
          :payload   payload ; better kw name?
          :stream-fn (subscription-streamer ctx msg state)
          :schema    schema})
       (swap! state assoc-in [:subs id])))


(defn execute-operation
  [{:keys [conn engine schema] :as ctx}
   {:keys [id payload]}
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
        ; Little more complex but less of a concurrency concern for
        ; client
        (swap! state assoc-in [:subs id] #())
        (let [parsed (e/parse engine {:schema schema, :payload payload})
              msg    {:id id, :payload (assoc payload :query parsed)}]
          (if (= :subscription
                 (e/op-kind engine {:schema  schema
                                    :payload (:payload msg)}))
            (execute-subscription ctx msg state)
            (execute-query ctx msg state)))
        (catch Exception _e
          ; log error
          (swap! state update :subs dissoc id)))))

(defn complete
  [_ctx {:keys [id]} state]
  (when-let [completer (get-in @state [:subs id])]
    (completer)
    (swap! state update :subs dissoc :id)))

(defn process [ctx msg state]
  (match [(:status @state) (:type msg)]
    [:init "connection_init"]
      (ack ctx msg state)
    [:ready "connection_init"]
      (close! (:conn ctx) :redundant-init)
    [:ready "ping"]
      (ping ctx msg state)
    [:ready "pong"]
      (pong ctx msg state)
    [:ready "subscribe"]
      (execute-operation ctx msg state)
    [:ready "complete"]
      (complete ctx msg state)
    :else
      (close! ctx :invalid-message)))

