(ns graphql.kit.util
  (:require
    [graphql.kit.compiler :as compiler]
    [graphql.kit.loader :as loader]
    [graphql.kit.runtime :as rt]))

(defn load-schema [schema]
  (cond
    (and (map? schema) (:path schema))
      [(loader/path rt/*loader* (:path schema))
       (dissoc schema :path)]
    (and (map? schema) (:resource schema))
      [(loader/resource rt/*loader* (:resource schema))
       (dissoc schema :resource)]
    (map? schema)
      [schema {}]
    :else
      ; assume :schema is a string and resource path
      [(loader/resource rt/*loader* schema) {}]))

(defn load+compile [schema opts]
  (let [[schema schema-opts] (load-schema schema)]
    (compiler/compile rt/*engine* schema (into schema-opts opts))))

