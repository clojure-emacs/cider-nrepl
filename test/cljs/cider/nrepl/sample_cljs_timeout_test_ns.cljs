(ns cider.nrepl.sample-cljs-timeout-test-ns
  "A ClojureScript test namespace whose async test never calls `done`, used to
  exercise the test middleware's await timeout. See clojure-emacs/cider#555."
  (:require
   [cljs.test :refer-macros [deftest is async]]))

(deftest never-completes-test
  (async _done
    ;; The assertion runs, but `_done` is never called, so the run never
    ;; finishes and the middleware must time out rather than hang.
         (is (= 1 1))))
