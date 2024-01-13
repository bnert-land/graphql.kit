(ns graphql.kit.util
  (:require
    [graphql.kit.loader :as loader]))

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

