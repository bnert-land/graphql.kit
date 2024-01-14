(ns graphql.kit.protos.loader)

(defprotocol SchemaLoader
  (path [this path] "Load a schema from a file path")
  (resource [this path] "Load a schema from a resource file"))

