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

(defn- take-response [taker]
  (-> taker
      (s/take!)
      #_:clj-kondo/ignore
      (d/timeout! 1000)
      (deref)
      (decode)))

(defn response? [world id m]
  (is (= m (take-response (get-in world [:proc id :taker]))))
  world)

(defn handshake [world conn-id]
  (-> world
     (message conn-id
       {:type const/connection-init})
     (response? conn-id
       {:type const/connection-ack})))

; merely checking we've received a corpus of messages,
; not relying on some order
;
; not thrilled w/ the name, can't think of anything better right now.
(defn responses-in-corpus? [world id xs]
  (doseq [_ xs]
    (let [resp (take-response (get-in world [:proc id :taker]))]
      (is (contains? xs resp))))
  world)

(defn responses? [world id xs]
  (doseq [x xs]
    (response? world id x))
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


(defn stream-range-resolver [_ctx {:keys [n], :or {n 10}} ->stream]
    ; Leaving the following code in as a reminder
    ;(d/loop [n' (range 0 n)]
    ;  (when (seq n')
    ;    (->stream {:n (first n'), :range n}))
    ;  (d/chain'
    ;    ; W/o this kind of timeout, message may arrove "out of order"
    ;    ; this is a smell that either:
    ;    ;   1. This lib is mis-using manifold/ordering primitives (most likely)
    ;    ;   2. Race condition in underlying manifold thread pool
    ;    ;
    ;    ; This does highlight needing the ability to specify behavior for
    ;    ; sending subscription messages (i.e. using java.util.concurrent.*Queue
    ;    ; as an intermediate stream).
    ;    (d/timeout! 500 ::timeout)
    ;    (fn [_]
    ;      (if (seq n')
    ;        (d/recur (rest n'))
    ;        ::exit))))
  (doseq [n' (range 0 n)]
    (->stream {:n n', :range n}))
  #(do
     #_nothing))

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

(deftest ws-protocol
  (testing "Handshake, then run a query, then close"
    (-> {:conn (conn!)}
        (schema!
          {:loader    (kit.loader/loader!)
           :engine    (kit.engine/engine!)
           :schema    {:resource "graphql/kit/test/schema/echo.edn"}
           :resolvers {:query {:Query/echo    echo-resolver
                               :Mutation/echo echo-resolver}}})
        (processor-from :conn)
        (handshake :conn)
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
           :type const/complete})))
  ; --
  (testing "Handshake, malformed input"
    (-> {:conn (conn!)}
        (schema!
          {:loader    (kit.loader/loader!)
           :engine    (kit.engine/engine!)
           :schema    {:resource "graphql/kit/test/schema/range.edn"}
           :resolvers {:subscription {:Subscription/range stream-range-resolver}}})
        (processor-from :conn)
        ; Handshake
        (handshake :conn)
        ; -- subscribe to range query
        (message :conn
          {:id            0
           :type          const/subscribe
           :payload       {:query "subscribe($n: Int) { range(n: $n) { n } }"
                           :variables     {:n 3}}})
        ; --
        (response? :conn
          {:id   0
           :type const/error
           :payload
           {:errors [{:locations [{:column nil, :line 1}]
                      :message   "mismatched input 'subscribe' expecting {'query', 'mutation', 'subscription', '{', 'fragment'}"}]}})))
  ; --
  (testing "Handshake, run a subscription, then close"
    (-> {:conn (conn!)}
        (schema!
          {:loader    (kit.loader/loader!)
           :engine    (kit.engine/engine!)
           :schema    {:resource "graphql/kit/test/schema/range.edn"}
           :resolvers {:subscription {:Subscription/range stream-range-resolver}}})
        (processor-from :conn)
        ; Handshake
        (handshake :conn)
        ; -- subscribe to range query
        (message :conn
          {:id            0
           :type          const/subscribe
           :payload       {:query     "subscription($n: Int) { range(n: $n) { n } }"
                           :variables {:n 3}}})
        ; --
        (responses-in-corpus? :conn
          #{{:id      0
             :type    const/next
             :payload {:data {:range {:n 0}}}}
            {:id      0
             :type    const/next
             :payload {:data {:range {:n 1}}}}
            {:id      0
             :type    const/next
             :payload {:data {:range {:n 2}}}}})
        ; --
        (message :conn
          {:id       0
           :type     const/complete}))))

