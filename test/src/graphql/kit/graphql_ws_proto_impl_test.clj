; Copyright (c) Brent Soles. All rights reserved.
(ns graphql.kit.graphql-ws-proto-impl-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [graphql.kit.encdec :refer [encode decode]]
    [graphql.kit.engines.lacinia :as kit.engine]
    [graphql.kit.loaders.aero :as kit.loader]
    [graphql.kit.protos.engine :as kit.e]
    [graphql.kit.servers.proto-impls.graphql-transport-ws.constants :as const]
    [graphql.kit.servers.proto-impls.graphql-transport-ws :as impl]
    [graphql.kit.util :as util]
    [manifold.deferred :as d]
    [manifold.stream :as s]))

(defn conn! []
  (let [sender (s/stream) ; i.e. sink, consumer
        taker  (s/stream)] ; i.e. source, producer
    {:sender sender
     :taker  taker
     :conn  (s/splice taker sender)}))

(defn schema! [world opts]
  (let [loaded (util/load-schema (:loader opts) (:schema opts))
        schema (kit.e/compile (:engine opts)
                              (-> opts
                                  (select-keys [:options :resolvers :scalars])
                                  (into {:schema loaded})))]
    (assoc world :gql-ctx (-> opts
                              (select-keys [:engine])
                              (into {:async?  false
                                     :request {}
                                     :schema  schema})))))

(defn processor-from [world id]
  (let [stream (get world id)]
    (impl/processor
      (into (get world :gql-ctx {})
            {:close! (impl/closer! s/close! (:conn stream))
             :conn   (:conn stream)}))
    (assoc-in world [:proc id] stream)))

(defn message [world id m]
  (s/put! (get-in world [:proc id :sender]) (encode m))
  world)

(defn response? [world id m]
  (is (= m (-> (get-in world [:proc id :taker])
               (s/take!)
               #_:clj-kondo/ignore
               (d/timeout! 500)
               (deref)
               (decode))))
  world)

(defn client-close [world id]
  (s/close! (get-in world [:proc id :conn]))
  (let [stream (get-in world [:proc id :conn])]
    (is (true? (s/closed? stream)))
    (is (true? (s/drained? stream))))
  world)


; --

(defn echo-resolver [_ctx {:keys [message] :as _args
                          :or   {message "i wet my arm pants"}} _data]
  {:message message})


; --

(deftest connection-init
  (testing "Initializing a connection \"flow\", and complete from client"
    (-> {:conn (conn!)}
        (processor-from :conn)
        (message :conn
          {:type const/connection-init})
        (response? :conn
          {:type const/connection-ack})
        (client-close :conn))))

(deftest single-ws-query+mut
  (testing "Initializing a connection them running a query and closing"
    (-> {:conn (conn!)}
        (schema!
          {:loader    (kit.loader/loader!)
           :engine    (kit.engine/engine!)
           :schema    {:resource "graphql/kit/test/schema/echo.edn"}
           :resolvers {:query {:Query/echo    echo-resolver
                               :Mutation/echo echo-resolver}}})
        (processor-from :conn)
        ; Handshake
        (message :conn
          {:type const/connection-init})
        (response? :conn
          {:type const/connection-ack})
        ; -- query
        (message :conn
          {:id   0
           :type const/subscribe
           :payload
           {:query     "query($msg: String) { echo(message: $msg) { message } }"
            :variables {:msg "me fail english"}}})
        (response? :conn
          {:id 0
           :type const/next
           :payload
           {:data {:echo {:message "me fail english"}}}})
        (response? :conn
          {:id 0
           :type const/complete})
        ; --
        (message :conn
          {:id   1
           :type const/subscribe
           :payload
           {:query     "mutation($msg: String) { echo(message: $msg) { message } }"
            :variables {:msg "thats umpossible"}}})
        (response? :conn
          {:id 1
           :type const/next
           :payload
           {:data {:echo {:message "thats umpossible"}}}})
        (response? :conn
          {:id 1
           :type const/complete}))))

