(ns cider.nrepl-test
  (:require
   [cider.nrepl :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest warmup-orchard-caches!
  @sut/initializer ;; Prevent concurrent calls to `sut/warmup-orchard-caches!`
  (testing "Can be executed without throwing exceptions"
    (is (pos? (sut/warmup-orchard-caches!))
        "Returns the keys count of the affected cache")))
