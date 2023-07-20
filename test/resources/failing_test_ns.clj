(ns failing-test-ns
  (:require [clojure.test :refer :all]))

(deftest fast-failing-test
  (is (= 0 1)))

(deftest slow-failing-test
  (Thread/sleep 1000)
  (is (= 0 1)))
