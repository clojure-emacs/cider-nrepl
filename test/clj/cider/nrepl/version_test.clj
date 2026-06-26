(ns cider.nrepl.version-test
  (:require
   [cider.nrepl.version :as v]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]))

(deftest version-string-test
  (is (string? v/version-string)))

(deftest version-test
  (is+ {:major some?
        :minor some?
        :incremental some?
        :version-string some?}
       v/version))
