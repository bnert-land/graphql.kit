(ns graphql.kit.runtimes.lacinia
  (:refer-clojure :exclude [compile])
  (:require
    [com.walmartlabs.lacinia :as lacinia]
    [com.walmartlabs.lacinia.constants :as l.constants]
    [com.walmartlabs.lacinia.executor :as l.executor]
    [com.walmartlabs.lacinia.parser :as l.parser]
    [com.walmartlabs.lacinia.schema :as l.schema]
    [com.walmartlabs.lacinia.util :as l.util]
    [graphql.kit.runtime :as rt]
    [graphql.kit.compiler :as compiler]
    [graphql.kit.engine :as engine]))

(defn as-parsed-query [{:keys [schema query opts]}]
  (if-not (string? query)
    query ; parsed
    (as-> [schema query] $
      (if-not (:operation-name opts)
        $
        (conj $ (:operation-name opts)))
      (apply l.parser/parse-query $))))

(defn query* [{:keys [schema query variables operationName ctx opts] :as ctx*}]
  (lacinia/execute-parsed-query
    (as-parsed-query ctx*)
    variables
    (into (or ctx {}) {:schema schema})
    ; TODO: this is gross, make it better
    (cond-> (or opts {})
      (and operationName (not (:operation-name opts)))
        (assoc :operation-name operationName))))

; should look @ lacinia pedestal for reference impl
; leave "empty" for now until understand the reference impl
; better
(defn subscription*
  [{:keys [ctx on-data schema query variables]
    :or   {ctx {}}}]
  (let [prepped (-> schema
                    (l.parser/parse-query query)
                    (l.parser/prepare-with-query-variables variables))]
    (l.executor/invoke-streamer
      (into ctx {l.constants/parsed-query-key prepped})
      on-data)))

(defn use-lacinia! []
  (let [l (reify
            compiler/SchemaCompiler
            (parse [_ schema options]
              (let [{:keys [query operationName]} options]
                (cond->> [schema query]
                  operationName
                    (conj operationName)
                  true
                    (apply l.parser/parse-query))))
            (prepare [_ query options]
              (let [{:keys [variables]} options]
                (l.parser/prepare-with-query-variables query variables)))
            (compile [_ schema-prelude options]
              (let [{:keys [resolvers scalars]} options
                    options (dissoc options :resolvers :scalars)]
                (cond-> schema-prelude
                  (:query resolvers)
                    (l.util/inject-resolvers (:query resolvers))
                  scalars
                    (l.util/inject-scalar-transformers scalars)
                  (:subscription resolvers)
                    (l.util/inject-streamers (:subscription resolvers))
                  true
                    (l.schema/compile options))))
            ;--
            engine/Engine
            (query [_ ctx]
              (query* ctx))
            (subscription [_ ctx]
              (subscription* ctx)))]
    (alter-var-root #'rt/*engine* (constantly l))
    (alter-var-root #'rt/*compiler* (constantly l))))

