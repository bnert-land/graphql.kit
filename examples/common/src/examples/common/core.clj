(ns examples.common.core
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]))

(defn load-dataset []
  (-> (io/resource "datasets/heros.edn")
      slurp
      edn/read-string))

(defn heros-db [heros]
  (atom
    (reduce
      (fn [m v]
        (let [table (get v :kind)
              id    (random-uuid)
              v'    (assoc v :id id)]
          (update m table (fnil assoc {}) id v')))
      {}
      heros)))

(defn heros-db! []
  (-> (load-dataset)
      (heros-db)))

(defn add-hero [db hero]
  (let [table (get hero :kind)
        id    (random-uuid)
        hero' (assoc hero :id id)]
    (swap! db update table assoc id hero')
    hero'))


(def scalars
  {:UUID
   {:parse #(when (string? %)
               (try
                 (parse-uuid %)
                 (catch Exception _e
                   nil)))
    :serialize #(when (uuid? %)
                  (str %))}})

