(ns cider.nrepl.middleware.test-report-counters-tests
  "Tests that verify *report-counters* is bound during test execution.
  Used by `cider.nrepl.middleware.test-test`."
  (:require
   [clojure.test :refer :all]))

(deftest report-counters-bound-test
  (is (some? clojure.test/*report-counters*)
      "*report-counters* should be bound during test execution"))
