(ns graphql.kit.ring.ws
  (:require
    [clojure.string :as str]
    [graphql.kit.encdec :refer [decode]]
    [graphql.kit.engine :as e]
    [graphql.kit.ring.proto-impls.graphql-transport-ws :as gql-transport-ws]
    [graphql.kit.util :refer [load-schema]]
    [ring.websocket :as ws]
    [taoensso.timbre :refer [debug]]))

(defn protocols [req]
  (-> req
      :headers
      (get "sec-websocket-protocol" "")
      (str/split #"," 16) ; why would there be more than 16 protocols?
      (set)))

(defn handle [ctx]
  (let [protos (protocols (:request ctx))
        state  (atom {:status :init, :subs {}, :params nil})]
    (cond
      (contains? protos gql-transport-ws/protocol-id)
        {::ws/protocol gql-transport-ws/protocol-id
         ::ws/listener
         {:on-open
          (fn [_socket]
            (debug "Socket opened"))
          :on-message
          (fn [socket msg]
            (when-let [m (decode msg)]
              ; this should be pretty much "stateless", given 
              (gql-transport-ws/process
                (assoc ctx :conn socket)
                m
                state)))
          :on-error
          (fn [_socket throwable]
            (debug throwable))
          :on-close
          (fn [_socket code reason]
            (debug "Socket closed" code reason)
            ; info args?
            (when (= :ready (:status @state))
              (swap! state assoc :status :closed)
              (doseq [closer (vals (:subs @state))]
                (closer))))}}
      :else
        {:status 400})))

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
       (handle {:engine engine, :request req, :schema schema'}))
      ([req res raise]
       (res (handle {:engine engine, :request req, :schema schema'}))))))

