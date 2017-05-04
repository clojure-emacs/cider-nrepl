(ns cider.nrepl.middleware.test-test
  (:require [clojure.test :refer :all]
            [cider.nrepl.middleware.test :as test]))

(deftest basic-sanity-test
  ;; Just make sure that the namespace loads properly and the macro
  ;; expands without errors. (See #264)
  (is (seq (macroexpand '(test/with-interruptible-eval {}
                           10))))
  (is (= (class @test/default-executor)
         java.util.concurrent.ThreadPoolExecutor)))

(deftest has-tests-errors []
  (is (test/has-tests? 'cider.nrepl.middleware.test-test))
  ;; clojure-emacs/cider#1940
  (is (not (test/has-tests? 'this.namespace.does.not.have.tests.or.error))))
