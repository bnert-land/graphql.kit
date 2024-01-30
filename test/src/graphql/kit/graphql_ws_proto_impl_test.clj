(ns graphql.kit.graphql-ws-proto-impl-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [graphql.kit.encdec :refer [encode decode]]
    [graphql.kit.servers.proto-impls.graphql-transport-ws.constants :as const]
    [graphql.kit.servers.proto-impls.graphql-transport-ws :as impl]
    [manifold.deferred :as d]
    [manifold.stream :as s]))

; not doing this connection mock correctly. Should look like:
;
; client put -> conn -> processor -> conn -> client take
;
; however, w/ a single stream, the library will "inifinte" loop, given
; the message is put back onto the same channel/stream.
;
; need to draw this out... too early in the morning...
(defn conn! []
  (let [in* (s/stream) ; i.e. sink, consumer
        out*  (s/stream)] ; i.e. source, producer
    ; would have in* out*, but aleph does out*, in*
    ;
    ; This is working... my is my intuition different than
    ; what is working...?
    {:sender in*
     :taker  out*
     :conn  (s/splice out* in*)}))



(defn processor-from [world id]
  (let [stream (get world id)]
    (impl/processor
      {:close! (impl/closer! s/close! (:conn stream))
       :conn   (:conn stream)})
    (assoc-in world [:proc id] stream)))

(defn message [world id m]
  (println "PUTTING" id m)
  (s/put! (get-in world [:proc id :sender]) (encode m))
  world)

(defn response? [world id m]
  (is (= m (-> (get-in world [:proc id :taker])
               (s/take!)
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

(deftest connection-init
  (testing "Initializing a connection \"flow\", and complete from client"
    (is (true? true))
    (-> {:conn (conn!), :op/id 0}
        (processor-from :conn)
        (message :conn
          {:type const/connection-init})
        (response? :conn
          {:type const/connection-ack})
        (client-close :conn))))

