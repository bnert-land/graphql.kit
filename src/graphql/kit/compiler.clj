(ns graphql.kit.compiler
  (:refer-clojure :exclude [compile]))

(defprotocol SchemaCompiler
  (parse [this schema-prelude options]
    "Parses a schema and (optionally) applies options")
  (prepare [this parsed-schema options]
    "Prepares a query with given options")
  (compile [this schema-or-prepped options]
    "Complies a schema given a \"raw\" or prepared schema"))

