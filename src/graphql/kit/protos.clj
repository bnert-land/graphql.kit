(ns graphql.kit.protos)

(defprotocol WebsocketCloser
  :extend-via-metadata true
  (close! [this reason message]
    "Closes connection with reason, message."))

