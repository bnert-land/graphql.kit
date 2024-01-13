(ns graphql.kit.ring.ws
  (:require
    [clojure.string :as str]
    [graphql.kit.encdec :refer [encode decode]]
    [graphql.kit.engine :as e]
    [graphql.kit.ring.proto-impls.graphql-transport-ws :as gql-transport-ws]
    [graphql.kit.util :refer [load-schema]]
    [ring.websocket :as ws]))

(defn protocols [req]
  (-> req
      :headers
      (get "sec-websocket-protocol" "")
      (str/split #"," 16) ; why would there be more than 16 protocols?
      (set)))

(defn handle [{:keys [engine request schema]}]
  (let [conn  (atom nil)
        state (atom {:status :init, :subs {}, :params nil})]
    {::ws/listener
     {:on-open
      (fn [socket]
        (reset! conn socket))
      :on-message
      (fn [socket msg]
        (when-let [m (decode msg)]
          (gql-transport-ws/process
            {:conn @conn, :schema schema, :request req}
            m
            state)))
      #_#_:on-pong
      (fn [socket buffer])
      #_#_:on-error
      (fn [socket throwable])
      #_#_:on-close
      (fn [socket code reason])
      #_#_:on-ping
      (fn [socket buffer])}}))

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
