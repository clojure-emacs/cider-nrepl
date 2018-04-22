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
                                                              :include       ["smoke"]
                                                              :exclude       ["integration"]})
          tests                             (keys (:cider.nrepl.middleware.test-filter-tests results))]
      ;(println "test result" test-result)
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests) "ns that contains smoke is present")
      (is (= 1 (count tests)) "only one test was run")
      (is (= :a-puff-of-smoke-test (first tests)) "only the test marked 'smoke' was run")))
  (testing "only test marked as smoke is run when test-ns is used"
    (let [{:keys [results] :as test-result} (session/message {:op            "test"
                                                              :ns            "cider.nrepl.middleware.test-filter-tests"
                                                              :include       ["smoke"]
                                                              :exclude       ["integration"]})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests) "ns that contains smoke is present")
      (is (= 1 (count tests)) "only one test was run")
      (is (= :a-puff-of-smoke-test (first tests)) "only the test marked 'smoke' was run")))
  (testing "only test not marked as integration is run when test-ns is used"
    (let [{:keys [results] :as test-result} (session/message {:op            "test"
                                                              :ns            "cider.nrepl.middleware.test-filter-tests"
                                                              :exclude       ["integration"]})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests) "ns that contains smoke is present")
      (is (= 2 (count tests)) "only one test was run")
      (is (= #{:a-puff-of-smoke-test :yet-an-other-test} (set tests)) "only the test marked 'smoke' was run")))
  (testing "marked test is still run if filter is not used"
    (let [{:keys [results] :as test-result} (session/message {:op            "test"
                                                              :ns            "cider.nrepl.middleware.test-filter-tests"})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests) "ns that contains smoke is present")
      (is (< 1 (count tests)) "more tests were run")
      (is ((set tests) :a-puff-of-smoke-test) "smoke test is still present without a filter"))))

(deftest list-only-smoke-tests
  (testing "only test marked as smoke is listed when list-all is run"
    (let [test-result (session/message {:op            "list-all-tests"
                                        :include       ["smoke"]
                                        :exclude       ["integration"]})
          puff-of-smoke-test-results (->> (:cider.nrepl.middleware.test-filter-tests test-result)
                                          (filter #(#{"a-puff-of-smoke-test"} (:name %)))
                                          first
                                          :test-results)]
      (is (contains? test-result :cider.nrepl.middleware.test-filter-tests) "ns for smoke test missing")
      (is (some #{"a-puff-of-smoke-test"} (map :name (:cider.nrepl.middleware.test-filter-tests test-result))) "smoke test var is not returned")
      (is (empty? puff-of-smoke-test-results) "test results for puff-of-smoke should be empty")))
  (testing "if test result is available it is returned when listing test vars"
    (session/message {:op      "test"
                      :ns      "cider.nrepl.middleware.test-filter-tests"
                      :include ["smoke"]
                      :exclude ["integration"]})
    (let [test-result (session/message {:op            "list-all-tests"
                                        :include       ["smoke"]
                                        :exclude       ["integration"]})
          puff-of-smoke-test-results (->> (:cider.nrepl.middleware.test-filter-tests test-result)
                                          (filter #(#{"a-puff-of-smoke-test"} (:name %)))
                                          first
                                          :test-results)]
      (is (seq puff-of-smoke-test-results) "test was run but no test results in test list message")
      (is (= "puff" (:message (first puff-of-smoke-test-results)))))))
