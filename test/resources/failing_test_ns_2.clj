(ns failing-test-ns2
  (:require [clojure.test :refer :all]))

(deftest two-clauses
  (is (= 0 (do
             (Thread/sleep 1000)
             1)))
  (is (= 2 3)))

(deftest uses-are
  (are [input expected] (= input (identity expected))
    0 1
    2 3))
