(ns cider.nrepl.sample-cljs-async-test-ns
  "ClojureScript test namespace with `cljs.test/async` tests, used to exercise
  the test middleware's awaiting of asynchronous tests. See clojure-emacs/cider#555."
  (:require
   [cljs.test :refer-macros [deftest is async]]))

(deftest async-passing-test
  (async done
         (js/setTimeout (fn [] (is (= 1 1)) (done)) 20)))

(deftest async-failing-test
  (async done
         (js/setTimeout (fn [] (is (= 1 2)) (done)) 20)))
