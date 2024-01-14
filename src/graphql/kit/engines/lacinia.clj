; Copyright (c) Brent Soles. All rights reserved.
(ns graphql.kit.engines.lacinia
  (:refer-clojure :exclude [compile])
  (:require
    [com.walmartlabs.lacinia :as lacinia]
    [com.walmartlabs.lacinia.constants :as l.constants]
    [com.walmartlabs.lacinia.executor :as l.executor]
    [com.walmartlabs.lacinia.parser :as l.parser]
    [com.walmartlabs.lacinia.schema :as l.schema]
    [com.walmartlabs.lacinia.util :as l.util]
    [graphql.kit.protos.engine :as e]))

(defn as-parsed-query [schema {:keys [query operationName]}]
  (let [parsed? (map? query)]
    (if parsed?
      query
      (cond
        operationName
          (l.parser/parse-query schema query operationName)
        :else
          (l.parser/parse-query schema query)))))

(defn compile* [{:keys [schema resolvers scalars options]}]
  (cond-> schema
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


; should look @ lacinia pedestal for reference impl
; leave "empty" for now until understand the reference impl
; better
(defn subscribe*
  [{:keys [ctx stream-fn schema payload]
    :or   {ctx {}}}]
  (let [prepped (-> schema
                    (as-parsed-query payload)
                    (l.parser/prepare-with-query-variables
                      (:variables payload)))]
    (l.executor/invoke-streamer
      (into ctx {l.constants/parsed-query-key prepped})
      stream-fn)))

(defn engine! []
  (reify e/Engine
    (compile   [_ ctx] (compile* ctx))
    (op-kind   [_ ctx] (op-kind* ctx))
    (parse     [_ ctx] (parse* ctx))
    (prep      [_ ctx] (prep* ctx))
    (query     [_ ctx] (query* ctx))
    (subscribe [_ ctx] (subscribe* ctx))))

