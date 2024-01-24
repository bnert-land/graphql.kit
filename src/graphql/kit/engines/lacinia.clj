; Copyright (c) Brent Soles. All rights reserved.
(ns graphql.kit.engines.lacinia
  (:refer-clojure :exclude [compile])
  (:require
    [com.walmartlabs.lacinia :as lacinia]
    [com.walmartlabs.lacinia.constants :as l.constants]
    [com.walmartlabs.lacinia.executor :as l.executor]
    [com.walmartlabs.lacinia.parser :as l.parser]
    [com.walmartlabs.lacinia.parser.schema :as l.p.schema]
    [com.walmartlabs.lacinia.resolve :as l.resolve]
    [com.walmartlabs.lacinia.schema :as l.schema]
    [com.walmartlabs.lacinia.util :as l.util]
    [graphql.kit.protos.engine :as e]
    ; yeah, you may not like manifold, but lemme tell ya, an extra
    ; well implemented dependency is better than a thread per query
    [manifold.deferred :as d]))

(defn as-parsed-query [schema {:keys [query operationName]}]
  (let [parsed? (map? query)]
    (if parsed?
      query
      (cond
        operationName
          (l.parser/parse-query schema query operationName)
        :else
          (l.parser/parse-query schema query)))))

(defn resolve-subscription-value [{:keys [ctx]} stream-fn]
  (fn resolve-subscription-value' [value]
    (cond
      (nil? value)
        ; If we get a nil value, signal to the stream to close
        (stream-fn value :close)
      (l.resolve/is-resolver-result? value)
        (l.resolve/on-deliver! value resolve-subscription-value')
      :else
        (d/chain'
          ; https://media.giphy.com/media/3ohuPEhzrxawUFvQn6/giphy-downsized.gif
          (d/future
            (l.executor/execute-query
              (into ctx {::l.executor/resolved-value value})))
          (fn [resolver-value]
            (let [result (d/deferred)]
              (l.resolve/on-deliver! resolver-value
                (fn [resolved]
                  ; handle nil w/ error?
                  (d/success! result resolved)))
              result))
          (fn [result]
            (stream-fn result nil))))))


(defn compile* [{:keys [schema resolvers scalars options]}]
  (cond-> schema
    (string? schema)
      (l.p.schema/parse-schema)
    scalars
      (l.util/inject-scalar-transformers scalars)
    (:query resolvers)
      (l.util/inject-resolvers (:query resolvers))
    (:subscription resolvers)
      (l.util/inject-streamers (:subscription resolvers))
    true
      (l.schema/compile options)))

(defn op-kind* [{:keys [schema payload]}]
  (-> (as-parsed-query schema payload)
      (l.parser/operations)
      :type))

(defn parse* [{:keys [schema payload]}]
  (l.parser/parse-query schema (:query payload)))

(defn prep* [{:keys [payload]}]
  (l.parser/prepare-with-query-variables (:query payload)
                                         (:variables payload)))

(defn query* [{:keys [ctx schema payload]
               :or   {ctx {}}}]
  (lacinia/execute-parsed-query
    ; parsed-query
    (as-parsed-query schema payload)
    ; variables
    (get payload :variables {})
    ; context
    (into ctx {:schema schema})
    ; options
    ; TODO: this is gross, make it better
    (cond-> {}
      (:operationName payload)
        (assoc :operation-name (:operationName payload)))))

(defn subscribe*
  [{:keys [ctx stream-fn schema payload]
    :or   {ctx {}}
    :as   ctx*}]
  (let [prepped (-> schema
                    (as-parsed-query payload)
                    (l.parser/prepare-with-query-variables
                      (:variables payload)))
        ; a prepared query already has the schema associated with it,
        ; and therefore, doesn't need to pe propagated to further calls
        ctx     (assoc ctx l.constants/parsed-query-key prepped)]
    (l.executor/invoke-streamer
      ctx
      (resolve-subscription-value (assoc ctx* :ctx ctx) stream-fn))))

(defn engine! []
  (reify e/Engine
    (compile   [_ ctx] (compile* ctx))
    (op-kind   [_ ctx] (op-kind* ctx))
    (parse     [_ ctx] (parse* ctx))
    (prep      [_ ctx] (prep* ctx))
    (query     [_ ctx] (query* ctx))
    (subscribe [_ ctx] (subscribe* ctx))))

