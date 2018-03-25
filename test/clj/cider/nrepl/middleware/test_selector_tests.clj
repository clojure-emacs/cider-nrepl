(ns cider.nrepl.middleware.test-selector-tests
  "empty tests for tests testing the selector feature
  in `cider.nrepl.middleware.test-test` namespace"
  (:require [clojure.test :refer :all]))

(deftest ^:smoke a-puff-of-smoke-test
  (is true "puff"))

(deftest some-other-test
  (is true "other"))

(deftest yet-an-other-test
  (is true "yet an other"))
