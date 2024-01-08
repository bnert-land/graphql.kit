(ns graphql.kit.engine)

(defprotocol Engine
  (query [this ctx]
    "Executes a query given a payload of:
    :query
    :operationName (optional)
    :variables (optional)
    :extensions (optional)")
  #_(query& [this ctx]
    "Executes a query asynchronously
    Needs to return a CompleteableFuture")
  (subscription [this ctx]
    "Executes a subscription given a payload of:
    :id
    :query
    :operationName (optional)
    :variables (optional)
    :extensions (optional)"))

