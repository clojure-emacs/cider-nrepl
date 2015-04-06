(ns cider.nrepl.middleware.util.misc-test
  (:require [clojure.test :refer :all]
            [cider.nrepl.middleware.util.misc :as misc]))

(deftest test-transform-value
  (is (= (misc/transform-value (array-map
                                :a "b"
                                "c" :d
                                :e false
                                :f [:g :h :i]
                                :j 4
                                :k nil))
         '{"a" "b", "c" "d", "e" "false", "f" ("g" "h" "i"), "j" 4, "k" nil}))
  (is (-> (misc/transform-value {:k (java.io.File. ".")})
          (get "k")
          java.io.File.
          .isAbsolute)))

(deftest test-as-sym
  (is (= nil (misc/as-sym nil)))
  (is (= nil (misc/as-sym 1)))
  (is (= nil (misc/as-sym '())))
  (is (= nil (misc/as-sym [])))
  (is (= nil (misc/as-sym {})))
  (is (= nil (misc/as-sym :WAT)))
  (is (= 'WAT (misc/as-sym "WAT")))
  (is (= 'WAT (misc/as-sym 'WAT))))
