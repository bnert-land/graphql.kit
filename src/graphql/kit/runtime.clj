(ns graphql.kit.runtime
  (:refer-clojure :exclude [compile])
  (:require
    [graphql.kit.loaders.default :as default]
    [graphql.kit.compiler :as c]
    [graphql.kit.engine :as e]))

(extend-type nil
  e/Engine
  (query [_ _]
    (throw (ex-info "nil engine" {})))
  (subscription [_ _]
    (throw (ex-info "nil engine" {})))

  c/SchemaCompiler
  (parse [_ _ _]
    (throw (ex-info "nil compiler" {})))
  (prepare [_ _ _]
    (throw (ex-info "nil compiler" {})))
  (compile [_ _ _]
    (throw (ex-info "nil compiler" {}))))

(def ^:dynamic *compiler* nil)
(def ^:dynamic *engine* nil)
(def ^:dynamic *loader* (default/loader!))
