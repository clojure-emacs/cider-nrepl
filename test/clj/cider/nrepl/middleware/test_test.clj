(ns cider.nrepl.middleware.test-test
  (:require [cider.nrepl.middleware.test :as test]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest basic-sanity-test
  ;; Just make sure that the namespace loads properly and the macro
  ;; expands without errors. (See #264)
  (is (seq (macroexpand '(test/with-interruptible-eval {}
                           10))))
  (is (= (class @test/default-executor)
         java.util.concurrent.ThreadPoolExecutor)))

(deftest has-tests-errors
  (is (test/has-tests? 'cider.nrepl.middleware.test-test))
  ;; clojure-emacs/cider#1940
  (is (not (test/has-tests? 'this.namespace.does.not.have.tests.or.error))))

(deftest only-smoke-test-run-test
  (testing "only test marked as smoke is run when test-all is used"
    (let [{:keys [results] :as test-result} (session/message {:op            "test-all"
                                                              :selector "smoke"})
          tests (keys (:cider.nrepl.middleware.test-selector-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-selector-tests) "ns that contains smoke is present")
      (is (= 1 (count tests)) "only one test was run")
      (is (= :a-puff-of-smoke-test (first tests)) "only the test marked 'smoke' was run")))
  (testing "only test marke as smoke is run when test-ns is used"
    (let [{:keys [results] :as test-result} (session/message {:op            "test"
                                                              :ns            "cider.nrepl.middleware.test-selector-tests"
                                                              :selector "smoke"})
          tests (keys (:cider.nrepl.middleware.test-selector-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-selector-tests) "ns that contains smoke is present")
      (is (= 1 (count tests)) "only one test was run")
      (is (= :a-puff-of-smoke-test (first tests)) "only the test marked 'smoke' was run")))
  (testing "marked test is still run if selector is not used"
    (let [{:keys [results] :as test-result} (session/message {:op            "test"
                                                              :ns            "cider.nrepl.middleware.test-selector-tests"})
          tests (keys (:cider.nrepl.middleware.test-selector-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-selector-tests) "ns that contains smoke is present")
      (is (< 1 (count tests)) "more tests were run")
      (is ((set tests) :a-puff-of-smoke-test) "smoke test is still present without a selector"))))
