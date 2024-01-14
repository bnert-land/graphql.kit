(ns graphql.kit.servers.ring.graphiql
  (:require
    [clojure.string :as str]
    [hiccup2.core :as h]))

(defn encode-value [v]
  (cond
    (string? v)
      (str "\"" v "\"")
    :else
      (str v)))

(defn map->js-obj-str [m]
  (str "{"
       (->> m
            (reduce-kv
              #(conj %1 (str (name %2) ": " (encode-value %3)))
              [])
            (str/join ","))
       "}"))

(defn document [opts]
  ; Adapted from: https://github.com/graphql/graphiql/blob/main/examples/graphiql-cdn/index.html
  ; with some minor modifications.
  (str
    (h/html
      [:html {:lang "en"}
       [:head
        [:style
         "body { height: 100%; margin: 0; width: 100%; overflow: hidden; }
         #graphiql { height: 100vh; }"]
        [:script
         {:crossorigin true,
          :src         "https://unpkg.com/react@18/umd/react.development.js"}]
        [:script
         {:crossorigin true,
          :src         "https://unpkg.com/react-dom@18/umd/react-dom.development.js"}]
        [:script
         {:src  "https://unpkg.com/graphiql/graphiql.min.js"
          :type "application/javascript"}]
        [:link
         {:href "https://unpkg.com/graphiql/graphiql.min.css"
          :rel "stylesheet"}]
        [:script
         {:crossorigin true
          :src         "https://unpkg.com/@graphiql/plugin-explorer/dist/index.umd.js"}]
        [:link
         {:href "https://unpkg.com/@graphiql/plugin-explorer/dist/style.css"
          :rel  "stylesheet"}]]
       [:body
        [:div {:id "graphiql"} "Loading..."]
        [:script
         (h/raw
           (format
             "const root = ReactDOM.createRoot(document.getElementById('graphiql'));
             const isSecure = window.location.protocol === 'https:';
             const defaultUrl = `${window.location.origin}/graphql`;
             const defaultSub = `${isSecure ? 'wss:' : 'ws:'}//${window.location.origin}/graphql/subscribe`;
             const fetcher = GraphiQL.createFetcher(%s);
             const explorerPlugin = GraphiQLPluginExplorer.explorerPlugin();
             root.render(
               React.createElement(GraphiQL, {
                 fetcher,
                 defaultEditorToolsVisibility: true,
                 plugins: [explorerPlugin],
               }),
             );"
             (cond-> {:url (get opts :url 'defaultUrl)}
               (:subscriptions? opts)
                 (assoc :subscriptionUrl 'defaultSub)
               (:subscriptionUrl opts)
                 (assoc :subscriptionUrl (:subscriptionUrl opts))
               true
                 (map->js-obj-str))))]]])))

(defn handler [opts]
  (let [rendered (document opts)
        allow?   #(or (get opts :enabled? false) 
                      (get % :graphql.kit/graphiql? false))]
    (fn
      ([req]
       (when (allow? req)
         {:status 200,
          :headers {"Content-Type" "text/html"}
          :body    rendered}))
      ([req res _raise]
       (when (allow? req)
         (res {:status 200
               :headers {"Content-Type" "text/html"}
               :body    rendered}))))))

