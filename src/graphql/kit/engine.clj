(ns graphql.kit.engine
  (:refer-clojure :exclude [compile]))

(defprotocol Engine
  (compile [this ctx])
  (parse [this ctx])
  (prep [thix ctx])
  (query [this ctx])
  (subscribe [this ctx]))

