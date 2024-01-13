(ns user)

(require
  '[aleph.http :as aleph]
  '[clojure.pprint :refer [pprint]]
  '[clj-commons.byte-streams :as bs]
  '[clojure.tools.namespace.repl :as ns.repl]
  '[clojure.core.match :refer [match]]
  '[beh.core :as beh]
  '[criterium.core :as cc]
  '[manifold.bus :as m.b]
  '[manifold.executor :as m.e]
  '[manifold.deferred :as m.d]
  '[manifold.stream :as m.s]
  '[jsonista.core :as json]
  '[ring.middleware.params :as mw.params]
  '[ring.adapter.jetty :as jetty])

(ns.repl/disable-unload!)
(ns.repl/disable-reload!)
(ns.repl/set-refresh-dirs "src/")

(beh/use-jsonista)

(def conns (atom {}))

(def event-bus (m.b/event-bus))

(m.b/active? event-bus :events)

(defn events-subscription [ctx args ->stream]
  (let [s (m.b/subscribe event-bus :events)]
    (m.s/consume
      #(do (println "event>" %)
           (->stream %))
      s)
    #(do
       (m.s/close! s))))

(def humans
  [{:id           (random-uuid)
    :name         "Han Solo"
    :originPlanet "not earth"
    :appearsIn    [:NEWHOPE :EMPIRE :JEDI]}])

(defn resolve-humans [_ctx _args _data]
  humans)

(defn wrap-json [handler]
  (fn [req]
    (let [b (json/read-value (:body req) json/keyword-keys-object-mapper)]
      (handler (update req :params (fnil into {}) b)))))

(def Uuid
    {:parse #(when (string? %)
               (try
                 (parse-uuid %)
                 (catch Exception _e
                   nil)))
     :serialize #(when (uuid? %)
                   (str %))})

(comment
  (def ev (m.b/subscribe event-bus :events))

  (m.s/consume
    (fn [e] (println "EE>" e))
    ev)

  (m.b/publish! event-bus :events
    {:id 100
     :kind :LOG,
     :name   "pod-racer-status"
     :origin "tatooine"
     :message "itsss woooorrrkkkiiinnng"})
)

(comment
  (ns.repl/refresh)
  (def kit-config
    {:graphql.kit/engine (graphql.kit.engines.lacinia/engine!)
     :graphql.kit/loader (graphql.kit.loaders.aero/loader!)
     :scalars            {:Uuid Uuid}
     :schema             {:resource "graphql/schema.edn"}
     :resolvers          {:query
                          {:Query/humans resolve-humans}
                          :subscription
                          {:Subscription/events events-subscription}}
     :options            {:executor  (m.e/execute-pool)}})
)

; Jetty
#_:clj-kondo/ignore
(comment
  (ns.repl/refresh)

  (def jetty-server
    (jetty/run-jetty
      (-> (graphql.kit.ring.http/handler kit-config)
          (mw.params/wrap-params)
          (wrap-json))
      {:port 9110
       :join? false}))
  (.stop jetty-server)

  (def jetty-ws-server
    (jetty/run-jetty
      (graphql.kit.ring.ws/handler kit-config)
      {:port 9111, :join? false}))
  (.stop jetty-ws-server)

  (cc/quick-bench ;=> ~263ns mean
    (try
      (deref (aleph/post "http://localhost:9110/"
                         {:as           :json
                          :accept       :json
                          :content-type :json
                          :form-params  {:query "{ humans { id name } }"}}))
      (catch Exception e
        (update (ex-data e) :body json/read-value))))

)


; Aleph
(comment
  (ns.repl/refresh)

  (def aleph-server
    (aleph/start-server
      (-> (graphql.kit.aleph.ws/handler kit-config)
          (mw.params/wrap-params)
          (wrap-json))
      {:port 9111}))

  (.close aleph-server)

  (try
    (def gql @(aleph/websocket-client "ws://localhost:9111"
               {:sub-protocols "graphql-transport-ws"}))
    (catch Exception e
      e))

  (m.s/close! gql)

  (m.s/consume
    (fn [m]
      (print "gql>")
      (pprint (json/read-value m))
      (println))
    gql)

  (m.s/put! gql
            (json/write-value-as-string
              {:type "connection_init", :payload {:id 0}}))

  (m.s/put! gql
            (json/write-value-as-string
              {:type "subscribe"
               :id 1
               :payload
               {:query "subscription { events { id origin kind } }"}}))

  (m.s/put! gql
            (json/write-value-as-string
              {:type "subscribe"
               :id 2
               :payload
               {:query "query { humans { id name originPlanet appearsIn }}"}}))


  (m.s/put! gql
            (json/write-value-as-string
              {:type "complete", :id 0}))
)


; testing proto vs multimethod cost
(comment

  ; -- 
  (defmulti graphql-proto (fn [_ x] (:type x)))

  (defmethod graphql-proto "connection_init"
    [_conn x]
    x)

  (defmethod graphql-proto "subscribe"
    [_conn x]
    x)

  (defmethod graphql-proto :default
    [_ _]
    nil)

  ; mean: 29.175341ns
  (cc/quick-bench
    (graphql-proto {} {:type "connection_init"}))


  ; -- 

  (defprotocol GraphqlProto
    (init [_ x] "x")
    (subscribe [_ x] "x"))

  (def graphql-proto'
    (reify GraphqlProto
      (init [_ x] x)
      (subscribe [_ x] x)))

  (def lut {"connection_init" init, "subscribe" subscribe})

  ; mean: 17.015173 ns
  (cc/quick-bench
    (let [x {:type "connection_init"}]
      (when-let [f (get lut (get x :type))]
        (f graphql-proto' x))))


  ; mean: 5 ns
  (cc/quick-bench
    (let [x {:type "connection_init"}
          y :initialized]
      (match [y (:type x)]
        [:created     "connection_init"] x
        [:initialized "connection_init"] x
        :else                            y)))

)
