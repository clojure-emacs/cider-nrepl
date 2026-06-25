(ns cider.nrepl.sample-cljs-test-ns
  "A small ClojureScript test namespace exercised by the cljs test middleware
  tests. See clojure-emacs/cider#555."
  (:require
   [cljs.test :refer-macros [deftest is testing]]))

(deftest passing-test
  (testing "a passing assertion"
    (is (= 1 1))))

(deftest failing-test
  (is (= 1 2)))

(deftest erroring-test
  (is (= :never (throw (js/Error. "boom")))))
