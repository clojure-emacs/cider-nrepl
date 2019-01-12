(ns cider.nrepl.middleware.test-test
  (:require
   [cider.nrepl.middleware.test :as test]
   ;; Ensure tested tests are loaded:
   cider.nrepl.middleware.test-filter-tests
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

(deftest only-selected-tests
  (testing "only single test is run with test"
    (are [tests] (let [{:keys [results] :as test-result}
                       (session/message
                        {:op "test"
                         :ns "cider.nrepl.middleware.test-filter-tests"
                         :tests (map name tests)})]
                   (is (= tests (keys (:cider.nrepl.middleware.test-filter-tests results)))))
      [:a-puff-of-smoke-test]
      [:a-smokey-test]
      [:a-puff-of-smoke-test :a-smokey-test]
      [:a-puff-of-smoke-test :a-smokey-test :yet-an-other-test])))

(deftest only-smoke-test-run-test-deprecated
  (testing "only test marked as smoke is run when test-all is used"
    (let [{:keys [results] :as test-result}
          (session/message {:op      "test-all"
                            :include ["smoke"]
                            :exclude ["integration"]})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests)
          "ns that contains smoke is present")
      (is (= 1 (count tests))
          "only one test was run")
      (is (= :a-puff-of-smoke-test (first tests))
          "only the test marked 'smoke' was run")))

  (testing "only test marked as smoke is run when test-ns is used"
    (let [{:keys [results] :as test-result}
          (session/message {:op      "test"
                            :ns      "cider.nrepl.middleware.test-filter-tests"
                            :include ["smoke"]
                            :exclude ["integration"]})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests)
          "ns that contains smoke is present")
      (is (= 1 (count tests))
          "only one test was run")
      (is (= :a-puff-of-smoke-test (first tests))
          "only the test marked 'smoke' was run")))

  (testing "only test not marked as integration is run when test-ns is used"
    (let [{:keys [results] :as test-result}
          (session/message {:op      "test"
                            :ns      "cider.nrepl.middleware.test-filter-tests"
                            :exclude ["integration"]})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests)
          "ns that contains smoke is present")
      (is (= 3 (count tests))
          "only one test was run")
      (is (= #{:a-puff-of-smoke-test :yet-an-other-test :test-with-map-as-message} (set tests))
          "only the test marked 'smoke' was run")))

  (testing "marked test is still run if filter is not used"
    (let [{:keys [results] :as test-result}
          (session/message {:op "test"
                            :ns "cider.nrepl.middleware.test-filter-tests"})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests) "ns that contains smoke is present")
      (is (< 1 (count tests)) "more tests were run")
      (is ((set tests) :a-puff-of-smoke-test) "smoke test is still present without a filter"))))

(deftest only-smoke-test-run-test
  (testing "only test marked as smoke is run when test-var-query is used"
    (let [{:keys [results] :as test-result}
          (session/message {:op "test-var-query"
                            :var-query {:include-meta-key ["smoke"]
                                        :exclude-meta-key ["integration"]}})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests)
          "ns that contains smoke is present")
      (is (= 1 (count tests))
          "only one test was run")
      (is (= :a-puff-of-smoke-test (first tests))
          "only the test marked 'smoke' was run")))

  (testing "only test marked as smoke is run when test-ns is used"
    (let [{:keys [results] :as test-result}
          (session/message {:op "test-var-query"
                            :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}
                                        :include-meta-key ["smoke"]
                                        :exclude-meta-key ["integration"]}})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests)
          "ns that contains smoke is present")
      (is (= 1 (count tests))
          "only one test was run")
      (is (= :a-puff-of-smoke-test (first tests))
          "only the test marked 'smoke' was run")))

  (testing "only test not marked as integration is run when test-ns is used"
    (let [{:keys [results] :as test-result}
          (session/message {:op "test-var-query"
                            :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}
                                        :exclude-meta-key ["integration"]}})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests)
          "ns that contains smoke is present")
      (is (= 3 (count tests))
          "only one test was run")
      (is (= #{:a-puff-of-smoke-test :yet-an-other-test :test-with-map-as-message} (set tests))
          "only the test marked 'smoke' was run")))

  (testing "marked test is still run if filter is not used"
    (let [{:keys [results] :as test-result}
          (session/message {:op "test-var-query"
                            :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}}})
          tests (keys (:cider.nrepl.middleware.test-filter-tests results))]
      (is ((set (keys results)) :cider.nrepl.middleware.test-filter-tests)
          "ns that contains smoke is present")
      (is (< 1 (count tests))
          "more tests were run")
      (is ((set tests) :a-puff-of-smoke-test)
          "smoke test is still present without a filter"))))

(deftest run-test-with-map-as-documentation-message
  (testing "documentation message map is returned as string"
    (let [{:keys [results] :as test-result}
          (session/message {:op "test"
                            :ns "cider.nrepl.middleware.test-filter-tests"
                            :tests ["test-with-map-as-message"]})]
      (is (= (str {:key "val"}) (-> results
                                    :cider.nrepl.middleware.test-filter-tests
                                    :test-with-map-as-message
                                    first
                                    :message))))))
