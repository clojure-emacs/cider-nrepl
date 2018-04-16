(ns cider.nrepl.middleware.test-filter-tests
  "empty tests for tests testing the filter feature
  in `cider.nrepl.middleware.test-test` namespace"
  (:require [clojure.test :refer :all]))

(deftest ^:smoke a-puff-of-smoke-test
  (is true "puff"))

(deftest ^:integration ^:smoke a-smokey-test
  (is true "puff"))

(deftest yet-an-other-test
  (is true "yet an other"))
