(ns graphql.kit.ring
  (:require
    [ring.core.protocols :as ring.proto]
    [jsonista.core :as json]))

; good idea?
(extend-type clojure.lang.PersistentArrayMap
  ring.proto/StreamableResponseBody
  (write-body-to-stream [body _response output-stream]
    (json/write-value output-stream body)
    (.close output-stream)))

(defn methods?
  ([req]
   (methods? req #{:get :post}))
  ([req methods*]
   (contains? methods* (get req :request-method))))

