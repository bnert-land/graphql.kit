(ns graphql.kit.aleph.ws
  (:require
    [aleph.http :as http]
    [clojure.string :as str]
    [clojure.core.match :refer [match]]
    [graphql.kit.engine :as engine]
    [graphql.kit.runtime :as rt]
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

(defn decode [msg]
  (try
    (json/read-value msg json/keyword-keys-object-mapper)
    (catch Exception _
      nil)))

(defn encode [msg]
  (try
    (json/write-value-as-bytes msg)
    (catch Exception _
      nil)))


; -- graphql-ws protocol implementation

(defn graphql-ws-ack [client {:keys [payload]} state]
  (m.d/chain'
    (m.s/put! client (encode {:type "connection_ack"}))
    (fn [ok?]
      (when ok?
        (swap! state assoc
                     :params payload
                     :state  :initialized)))))

(defn graphql-ws-too-many-inits [client]
  (http/websocket-close! client 4429 "Too many initialisation requests"))

(defn on-data [client id]
  (fn [data]
    (m.s/put! client
      (encode {:id id, :payload data, :type "next"}))))

; TODO: need to also handle query/mutation for correctness
(defn graphql-ws-subscribe
  [{:keys [client request schema]} {:keys [id payload]} state]
  (println "SUBSCRIBE" id payload state)
  (try
    (if (contains? (:subs @state) id)
      (http/websocket-close! 4409
                             (str "Subscriber for " id " already exists"))
      (->> (engine/subscription
             rt/*engine*
             (into payload
                   {:ctx      {:graphql.kit/request request
                               :graphql.kit/params (:params @state)}
                    :on-data  (on-data client id)
                    :schema   schema}))
           (swap! state assoc-in [:subs id])))
    (catch Exception e
      (println e))))

(defn graphql-ws-complete [_ {:keys [id]} state]
  (when-let [cleanup (get-in @state [:subs id])]
    (m.d/chain (cleanup)
      (fn [_]
        (swap! state update :subs dissoc id)))))

(defn graphql-ws-invalid-message [{:keys [client]} _msg _state]
  (http/websocket-close! client 4400 "Invalid message"))

(defn graphql-ws [req c schema]
  (let [ctx   {:client c, :request req, :schema schema}
        state (atom {:state :created, :subs {}, :params nil})]
    (->> c
        (m.s/consume
          (fn [message]
            (let [msg (decode message)]
              (println "MSG" msg)
              (println "STT" @state)
              ; why not use a multi-method? is there a perf hit?
              ; how fast should we go in this fn?
              ; should the operations/decision here be wrapped
              (match [(:state @state) (:type msg)]
                [:created "connection_init"]
                  (graphql-ws-ack c msg state)
                ; --
                [:initialized "connection_init"]
                  (graphql-ws-too-many-inits c)
                ; --
                [:initialized "subscribe"]
                  (graphql-ws-subscribe ctx msg state)
                ; --
                [:initialized "complete"]
                  (graphql-ws-complete ctx msg state)
                :else
                  (graphql-ws-invalid-message ctx msg state))))))
    (m.s/on-closed c
      (fn []
        (swap! state assoc :state :closed)
        (doseq [sub (vals (:subs @state))]
          ; should be a cleanup fn?
          (when (fn? sub)
            (sub)))))))

; --

(defn handle [req schema]
  ; TODO: handle non websocket request
  (let [protos (protocols req)]
    #_:clj-kondo/ignore
    (m.d/let-flow [c (http/websocket-connection
                       req
                       {:headers {"sec-websocket-protocol"
                                  "graphql-transport-ws"}})]
      ; there are actually a couple of protoocols which should be
      ; supported: legacy (graphql-ws) and graphql-ws (graphql-transport-ws)
      (cond
        (contains? protos "graphql-transport-ws")
          (graphql-ws req c schema)
        #_#_(contains? protos "graphql-ws")
          (subscriptions-transport-ws req c schema)
        :else
          (do
            (println "HERE")
            ; Don't know what else to do here rn
            (http/websocket-close! c 4400 "Invalid subprotocol")))))
    nil)

(defn handler [{:keys [resolvers scalars schema]
                :or   {resolvers          {}
                       scalars            {}}}]
  (let [schema' (load+compile schema
                              {:resolvers resolvers
                               :scalars   scalars})]
    (fn graphql-ws-handler
      ([req]
       (handle req schema'))
      ([req res raise]
       (res (handle req schema'))))))
