(ns cider.nrepl.middleware.test-ns-hook-tests
  "Fixture namespace for issue #680: its assertions run only through a
  `test-ns-hook`, with no standalone `deftest` vars for the var query to
  discover. Used to verify the test ops still honor the hook."
  (:require
   [clojure.test :refer [is testing]]))

(defn a-hook-driven-check []
  (testing "ran via test-ns-hook"
    (is (= :ok :ok))))

(defn test-ns-hook []
  (a-hook-driven-check))
