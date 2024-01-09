(ns user)

(require
  '[aleph.http :as aleph]
  '[clojure.pprint :refer [pprint]]
  '[clj-commons.byte-streams :as bs]
  '[clojure.tools.namespace.repl :as ns.repl]
  '[beh.core :as beh]
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
       (println "closing" ctx)
       (m.s/close! s))))

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

;(defn handle-ws [req]
;  (-> (m.d/let-flow [conn (http/websocket-connection req)]
;        (if-not conn
;          {:status 400
;           :headers {"Content-Type" "application/text"}
;           :body    "Expected websocket request"}
;          (do
;            (m.d/loop []
;              (m.d/chain (m.s/take! conn ::closed)
;                (fn [v]
;                  (println "server>" v)
;                  v)
;                (fn [v]
;                  (when-not (= ::closed v)
;                    (m.d/recur)))))
;            (m.s/connect (m.b/subscribe eb :log) conn)
;            nil)))
;      (m.d/catch
;        (fn [e]
;          (println e)
;          {:status 400
;           :headers {"Content-Type" "applicatin/text"}
;           :body    "Unable to create websocket"}))))

;(defn handle-not-found [_]
;  {:status 404})

;(comment
;  (def s
;    (http/start-server
;      (fn [req]
;        (case [(:request-method req) (:uri req)]
;          [:get "/graphql/ws"] (graphql.kit.alqph.ws/handler
;          #_default    (handle-not-found req)))
;      {:port 9109}))
;
;  (.close s)
;
;  (def conn @(http/websocket-client
;               "ws://localhost:9109/ws"
;               {:sub-protocols "graphql-transport-ws,imateapot"}))
;  (m.s/close! conn)
;
;  (m.s/consume
;    (fn [m]
;      (println "client>" m))
;    conn)
;
;  (m.s/put! (:conn @conns) "hello")
;
;  (m.b/publish! eb :log "log event 0")
;
;  (m.s/put! conn "hi")
;)

; Jetty
#_:clj-kondo/ignore
(comment
  (ns.repl/refresh)

  (graphql.kit.runtimes.lacinia/use-lacinia!)
  (graphql.kit.loaders.aero/use-aero-loader!)

  graphql.kit.runtime/*engine*
  graphql.kit.runtime/*compiler*
  graphql.kit.runtime/*loader*

  

  (def humansq
    "{ humans { id name } }")

  (def rhandler
    (graphql.kit.ring.http/handler
      {:scalars   {:Uuid Uuid}
       :schema    {:resource "graphql/schema.edn"}
       :resolvers {}
       :executor  (m.e/execute-pool)}))


  (def jetty-server
    (jetty/run-jetty
      (-> rhandler
          (mw.params/wrap-params)
          (wrap-json))
      {:port 9110
       :join? false}))
  (.stop jetty-server)

  (try
    (deref (http/post "http://localhost:9110/"
                      {:as           :json
                       :accept       :json
                       :content-type :json
                       :form-params  {:query "{ humans {id name} }"}}))
    (catch Exception e
      (update (ex-data e) :body json/read-value)))



  ;(defn sub-lacinia-resolver [ctx args source-stream]
  ;  (let [t (thread
  ;            (loop []
  ;              (let [value (take-from queue)]
  ;                ; is there a nice way to "adapt" aleph to
  ;                ; the underlying
  ;                ; for graphql.kit.ring.ws, this calls (ring.ws/send)
  ;                ; for graphql.kit.aleph.ws, this calls (put! ...)
  ;                (source-stream value)
  ;              (recur)))]
  ;    #(.kill t))))
)


; Aleph
(comment
  (ns.repl/refresh)

  (graphql.kit.runtimes.lacinia/use-lacinia!)
  (graphql.kit.loaders.aero/use-aero-loader!)

  graphql.kit.runtime/*engine*
  graphql.kit.runtime/*compiler*
  graphql.kit.runtime/*loader*



  (def aleph-server
    (aleph/start-server
      (-> (graphql.kit.aleph.ws/handler
            {:scalars   {:Uuid Uuid}
             :schema    {:resource "graphql/schema.edn"}
             :resolvers {:subscription
                         {:Subscription/events events-subscription}}
             :executor  (m.e/execute-pool)})
          (mw.params/wrap-params)
          (wrap-json))
      {:port 9111}))

  (.close aleph-server)

  (def gql @(aleph/websocket-client "ws://localhost:9111"
             {:sub-protocols "graphql-transport-ws"}))

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
              {:type "complete", :id 0}))
)
