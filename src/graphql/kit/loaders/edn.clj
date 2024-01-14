; Copyright (c) Brent Soles. All rights reserved.
(ns graphql.kit.loaders.edn
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [graphql.kit.protos.loader :as loader]))

(defn loader! []
  (reify loader/SchemaLoader
    (path [_ path]
      (let [f (io/as-file path)]
        (assert (.exists ^java.io.File f) (str "Schema path does not exist: " path))
        (-> f slurp edn/read-string)))
    (resource [_ path]
      (let [r (io/resource path)]
        (assert r (str "Schema resource does not exist: " path))
        (-> r slurp edn/read-string)))))

