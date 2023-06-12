(ns cider.log.event-test
  (:require [cider.log.event :as event]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.set :as set]
            [cider.log.framework :as framework]
            [cider.log.test :as test]
            [clojure.test.check.generators :as gen]))

(stest/instrument)

(def frameworks
  (vals (framework/resolve-frameworks)))

(defspec test-search
  (prop/for-all
   [{:keys [levels]} (gen/elements frameworks)
    criteria (s/gen :cider.log.event/search)
    events (s/gen (s/coll-of :cider.log/event))]
   (every? #(s/valid? :cider.log/event %)
           (event/search levels criteria events))))

(defspec test-search-end-time
  (prop/for-all
   [{:keys [levels]} (gen/elements frameworks)
    end-time (s/gen :cider.log.filter/end-time)
    events (s/gen (s/coll-of :cider.log/event))]
   (every? #(< (:timestamp %) end-time)
           (event/search levels {:filters {:end-time end-time}} events))))

(defspec test-search-exceptions
  (prop/for-all
   [{:keys [levels]} (gen/elements frameworks)
    exceptions (s/gen :cider.log.filter/exceptions)
    events (s/gen (s/coll-of :cider.log/event))]
   (let [opts {:filters {:exceptions exceptions}}
         events-found (event/search levels opts events)]
     (set/subset? (set (map :exception events-found))
                  (set (map :exception events))))))

(defspec test-search-level
  (prop/for-all
   [[framework events criteria]
    (gen/let [framework (gen/elements frameworks)
              level (gen/one-of [(gen/elements (map :name (:levels framework)))])
              events (gen/vector (test/event-gen framework) 3)]
      [framework events {:filters {:level level}}])]
   (let [level->weight (into {} (map (juxt :name :weight) (:levels framework)))
         min-weight (level->weight (-> criteria :filters :level))]
     (every? #(>= (level->weight (:level %)) min-weight)
             (event/search (:levels framework) criteria events)))))

(defspec test-search-loggers
  (prop/for-all
   [{:keys [levels]} (gen/elements frameworks)
    loggers (s/gen :cider.log.filter/loggers)
    events (s/gen (s/coll-of :cider.log/event))]
   (let [opts {:filters {:loggers loggers}}
         events-found (event/search levels opts events)]
     (set/subset? (set (map :logger events-found))
                  (set (map :logger events))))))

(defspec test-search-limit
  (prop/for-all
   [{:keys [levels]} (gen/elements frameworks)
    limit (s/gen :cider.log.pagination/limit)
    events (s/gen (s/coll-of :cider.log/event))]
   (>= limit (count (event/search levels {:limit limit} events)))))

(defspec test-search-offset
  (prop/for-all
   [{:keys [levels]} (gen/elements frameworks)
    offset (s/gen :cider.log.pagination/limit)
    events (s/gen (s/coll-of :cider.log/event))]
   (= (drop offset events)
      (event/search levels {:offset offset} events))))

(defspec test-search-start-time
  (prop/for-all
   [{:keys [levels]} (gen/elements frameworks)
    start-time (s/gen :cider.log.filter/start-time)
    events (s/gen (s/coll-of :cider.log/event))]
   (every? #(>= (:timestamp %) start-time)
           (event/search levels {:filters {:start-time start-time}} events))))
