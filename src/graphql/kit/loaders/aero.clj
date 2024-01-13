(ns graphql.kit.loaders.aero
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [graphql.kit.loader :as loader]))

(defn loader! []
  (reify loader/SchemaLoader
    (path [_ path]
      (let [f (io/as-file path)]
        (assert (.exists ^java.io.File f) (str "Schema path does not exist: " path))
        (aero/read-config f)))
    (resource [_ path]
      (let [r (io/resource path)]
        (assert r (str "Schema resource does not exist: " path))
        (aero/read-config r {:resolver aero/resource-resolver})))))

