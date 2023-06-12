(ns cider.log.appender-test
  (:require [cider.log.appender :as appender]
            [cider.log.specs]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]))

(stest/instrument)

(def appender
  (appender/make-appender {:id "my-appender" :levels []}))

(defspec test-add-consumer
  (prop/for-all
   [events (s/gen (s/coll-of :cider.log/event))]
   (let [captured-events (atom [])
         consumer {:id "my-consumer"
                   :filters {}
                   :callback #(swap! captured-events conj %2)}
         appender (appender/add-consumer appender consumer)]
     (let [appender (reduce appender/add-event appender events)]
       (appender/remove-consumer appender consumer)
       (= events @captured-events)))))

(defspec test-clear
  (prop/for-all
   [events (s/gen (s/coll-of :cider.log/event))]
   (-> (reduce appender/add-event appender events)
       (appender/clear)
       (appender/events)
       (empty?))))

(defspec test-event
  (prop/for-all
   [events (s/gen (s/coll-of :cider.log/event))]
   (let [appender (reduce appender/add-event appender events)]
     (= (appender/events appender)
        (map #(appender/event appender (:id %))
             (appender/events appender))))))

(defspec test-events
  (prop/for-all
   [events (s/gen (s/coll-of :cider.log/event))]
   (let [appender (reduce appender/add-event appender events)]
     (= (take (:size appender) (reverse events))
        (appender/events appender)))))

(defspec test-remove-consumer
  (prop/for-all
   [events (s/gen (s/coll-of :cider.log/event))]
   (let [captured-events (atom [])
         consumer {:id "my-consumer"
                   :filters {}
                   :callback #(swap! captured-events conj %2)}
         appender (appender/add-consumer appender consumer)
         appender (reduce appender/add-event appender events)]
     (let [appender (appender/remove-consumer appender consumer)]
       (doseq [event events] (appender/add-event appender event))
       (= events @captured-events)))))
