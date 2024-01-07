(ns user)

(require
  '[aleph.http :as http]
  '[manifold.bus :as m.b]
  '[manifold.deferred :as m.d]
  '[manifold.stream :as m.s])

(def conns (atom {}))

(def eb (m.b/event-bus))

(defn handle-ws [req]
  (-> (m.d/let-flow [conn (http/websocket-connection req)]
        (if-not conn
          {:status 400
           :headers {"Content-Type" "application/text"}
           :body    "Expected websocket request"}
          (do
            (m.d/loop []
              (m.d/chain (m.s/take! conn ::closed)
                (fn [v]
                  (println "server>" v)
                  v)
                (fn [v]
                  (when-not (= ::closed v)
                    (m.d/recur)))))
            (m.s/connect (m.b/subscribe eb :log) conn)
            nil)))
      (m.d/catch
        (fn [e]
          (println e)
          {:status 400
           :headers {"Content-Type" "applicatin/text"}
           :body    "Unable to create websocket"}))))

(defn handle-not-found [_]
  {:status 404})

(comment
  (def s
    (http/start-server
      (fn [req]
        (case [(:request-method req) (:uri req)]
          [:get "/ws"] (handle-ws req)
          #_default    (handle-not-found req)))
      {:port 9109}))

  (.close s)

  (def conn @(http/websocket-client "ws://localhost:9109/ws"))

  (m.s/consume
    (fn [m]
      (println "client>" m))
    conn)

  (m.s/put! (:conn @conns) "hello")

  (m.b/publish! eb :log "log event 0")

  (m.s/put! conn "hi")
)
