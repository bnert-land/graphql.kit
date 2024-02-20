; Copyright (c) Brent Soles. All rights reserved.
(ns graphql.kit.loaders.aero
  (:require
    [aero.core :as aero]
    [clojure.java.io :as io]
    [graphql.kit.protos.loader :as loader]))

(defn loader! []
  (reify loader/SchemaLoader
    (path [_ path]
      (let [f (io/as-file path)]
        (assert (.exists ^java.io.File f)
                (str "Path does not exist: " path))
        (aero/read-config f)))
    (resource [_ path]
      (let [r (io/resource path)]
        (assert r (str "Resource does not exist: " path))
        (aero/read-config r {:resolver aero/resource-resolver})))))

