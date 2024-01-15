(ns examples.common.lacinia-engine.star-wars.resolvers
  (:require
    [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
    [examples.common.core :as ex.core]
    [manifold.bus :as m.b]
    [manifold.stream :as m.s]
    [taoensso.timbre :refer [info]]))

(def bus (m.b/event-bus))

(def db (ex.core/heros-db!))

(defn tag [x]
  (case (:kind x)
    :droid    (tag-with-type x :Droid)
    :human    (tag-with-type x :Human)
    #_default x))

; -- Query Resolvers

(defn droid [_ctx {:keys [id]} _value]
  (get-in @db [:droid id]))

(defn droids [_ctx {:keys [episode]} _value]
  (cond->> (-> @db :droid vals)
    episode
       (filter #(contains? (:appearsIn %) episode))
    true
       (sort-by :name)))

(defn hero [_ctx {:keys [id]} _value]
  (tag
    (or (get-in @db [:human id])
        (get-in @db [:droid id]))))

(defn heros [_ctx {:keys [episode]} _value]
  (cond->> (concat (vals (:human @db))
                   (vals (:droid @db)))
    episode
      (filter #(contains? (:appearsIn %) episode))
    true
      (sort-by :name)
    true
      (map tag)))

(defn human [_ctx {:keys [id]} _value]
  (get-in @db [:human id]))

(defn humans [_ctx {:keys [episode]} _value]
  (cond->> (-> @db :human vals)
    episode
      (filter #(contains? (:appearsIn %) episode))
    true
      (sort-by :name)))


; -- Mutation resolvers

(defn add-droid [_ctx args _value]
  (let [args'  (assoc args :kind :droid)
        result (tag (ex.core/add-hero db args'))]
    (m.b/publish! bus :events
      {:kind :ENTITY_ADDED
       :data result})
    result))


(defn add-human [_ctx args _value]
  (let [args'  (assoc args :kind :human)
        result (tag (ex.core/add-hero db args'))]
    (m.b/publish! bus :events
      {:kind :ENTITY_ADDED
       :data (tag result)})
    result))


; -- Subscription Resolvers

(defn events [_ctx _args ->stream]
  (let [events (m.b/subscribe bus :events)]
    (info "Subscribing to events")
    (m.s/consume ->stream events)
    #(do
       (info "Closing subscription")
       (m.s/close! events))))

