(ns graphql.kit.encdec
  (:require
    [jsonista.core :as json]))

(defn decode [msg]
  (try
    (json/read-value msg json/keyword-keys-object-mapper)
    (catch Exception _
      nil)))

(defn encode [msg]
  (try
    (json/write-value-as-string msg)
    (catch Exception _
      nil)))

