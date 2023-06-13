(ns cider.log.test
  (:require [cider.log.specs]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test.check.generators :as gen]))

(stest/instrument)

(defn- exception-gen []
  (->> (gen/tuple gen/string-alphanumeric
                  (gen/map gen/keyword gen/any-printable-equatable))
       (gen/fmap (fn [[msg data]] (ex-info msg data)))))

(defn event-gen [framework]
  (->> (gen/tuple (s/gen :cider.log/event)
                  (gen/elements (:levels framework))
                  (exception-gen))
       (gen/fmap (fn [[event level exception]]
                   (cond-> (assoc event :level (:name level))
                     (= :error (:category level))
                     (assoc :exception exception))))))
