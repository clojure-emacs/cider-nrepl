(ns cider.nrepl.middleware.version-test
  (:require
   [cider.nrepl.version :as v]
   [clojure.test :refer :all]))

(deftest version-string-test
  (is (string? v/version-string)))

(deftest version-test
  (is (contains? v/version :major))
  (is (contains? v/version :minor))
  (is (contains? v/version :incremental))
  (is (contains? v/version :version-string)))
