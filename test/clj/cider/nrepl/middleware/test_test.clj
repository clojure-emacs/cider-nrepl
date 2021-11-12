(ns cider.nrepl.middleware.test-test
  (:require
   [cider.nrepl.middleware.test :as test]
   ;; Ensure tested tests are loaded:
   cider.nrepl.middleware.test-filter-tests
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all])
  (:import
   (clojure.lang ExceptionInfo)))

(use-fixtures :each session/session-fixture)

(deftest basic-sanity-test
  ;; Just make sure that the namespace loads properly and the macro
  ;; expands without errors. (See #264)
  (is (seq (meta #'test/handle-test))))

(deftest only-selected-tests
  (testing "only single test is run with test"
    (are [tests] (let [{:keys [results] :as test-result}
                       (session/message
                        {:op "test"
                         :ns "cider.nrepl.middleware.test-filter-tests"
                         :tests (map name tests)})]
                   (is (= tests (keys (:cider.nrepl.middleware.test-filter-tests results))))
                   true)
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

(deftest handling-of-tests-with-throwing-fixtures
  (require 'cider.nrepl.middleware.test-with-throwing-fixtures)
  (testing "If a given deftest's fixture throw an exception, those are gracefully handled"
    (let [{{{[{:keys [error]}] :cider.nrepl.middleware.test/unknown} :cider.nrepl.middleware.test-with-throwing-fixtures} :results
           :keys [summary status]
           :as test-result}
          (session/message {:op "test"
                            :ns "cider.nrepl.middleware.test-with-throwing-fixtures"})]
      (testing (pr-str test-result)
        (is (= error
               "clojure.lang.ExceptionInfo: I'm an exception inside a fixture! {:data 42}"))
        (is (= summary
               {:error 1, :fail 0, :ns 1, :pass 0, :test 0, :var 0}))
        (is (= status #{"done"}))))))

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

(deftest print-object-test
  (testing "uses println for matcher-combinators results, otherwise invokes pprint"
    (is (= "{no quotes}\n"
           (#'test/print-object (with-meta {"no" "quotes"} {:type :matcher-combinators.clj-test/mismatch})))
        "println is chosen, as indicated by strings printed without quotes")
    (is (= "{:a\n (\"a-sufficiently-long-string\"\n  \"a-sufficiently-long-string\"\n  \"a-sufficiently-long-string\")}\n"
           (#'test/print-object {:a (repeat 3 "a-sufficiently-long-string")}))
        "pprint is chosen, as indicated by quoted strings and newlines")
    (is (= "{:a \"b\", :c \"42\"}\n"
           (#'test/print-object (with-meta {:a "b" :c "42"} {:type ::mismatch})))
        "pprint is chosen, because :type does not match matchers-combinators keyword")))

(deftest test-result-test
  (testing "It passes `:error`s to `test/*test-error-handler*`"
    (let [proof (atom [])
          exception (ex-info "." {::unique (rand)})]
      (binding [test/*test-error-handler* (fn [e]
                                            (swap! proof conj e))]
        (with-out-str
          (test/test-result 'some-ns
                            #'+
                            {:type :error
                             :actual exception}))
        (is (= [exception]
               @proof))))))

(defn throws []
  (throw (ex-info "." {})))

(defn comparable-stack-frame
  "A stack-frame map without varying parts that make testing more cumbersome."
  [stack-frame]
  (dissoc stack-frame :file-url :line))

(deftest stack-frame-test
  (let [e (try
            (throws)
            (catch ExceptionInfo e
              e))]
    (is (= {:fn "throws"
            :method "invokeStatic"
            :ns "cider.nrepl.middleware.test-test"
            :name "cider.nrepl.middleware.test_test$throws/invokeStatic"
            :file "test_test.clj"
            :type :clj
            :var "cider.nrepl.middleware.test-test/throws"
            :class "cider.nrepl.middleware.test_test$throws"
            :flags #{:project :clj}}
           (comparable-stack-frame (test/stack-frame e throws)))
        "Returns a map representing the stack frame of the precise function
that threw the exception")))

(deftest stack-frame-line-test
  (let [e (try
            (throws)
            (catch ExceptionInfo e
              e))]
    ;; NOTE this offset is subject to formatting of this test
    (is (= (+ 2 (:line (meta #'stack-frame-line-test)))
           (:line (test/stack-frame
                   e
                   (-> #'stack-frame-line-test meta :test))))
        "Returns the line of the exception")))
