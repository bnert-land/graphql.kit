; Copyright (c) Brent Soles. All rights reserved.
(ns graphql.kit.util
  (:require
    [clojure.string :as str]
    [graphql.kit.protos.loader :as loader]
    [graphql.kit.protos.engine :as kit.e]))

(defn load-schema [loader* schema]
  (cond
    (and (map? schema) (:path schema))
      (loader/path loader* (:path schema))
    (and (map? schema) (:resource schema))
      (loader/resource loader* (:resource schema))
    (map? schema)
      schema
    :else
      ; assume :schema is a string and resource path
      (loader/resource loader* schema)))

(defn protocols [req]
  (-> req
      :headers
      (get "sec-websocket-protocol" "")
      (str/split #"," 16) ; why would there be more than 16 protocols?
      (set)))

(defn handle->ring-handler [handle]
  (fn handler-generator
    [{:graphql.kit/keys [engine loader]
      :keys             [options resolvers scalars schema]
      :or               {schema    {:resource "graphql.kit/schema.edn"}
                         resolvers {}
                         scalars   {}}}]
    (let [loaded  (load-schema loader schema)
          schema' (kit.e/compile engine
                                 {:options   options
                                  :resolvers resolvers
                                  :scalars   scalars
                                  :schema    loaded})]

      (fn handler
        ([req]
         (handle
           {:async?  false
            :engine  engine
            :request req
            :schema  schema'}))
        ([req res _raise]
         (res
           (handle
             {:async?  true
              :engine  engine
              :request req
              :schema  schema'})))))))


