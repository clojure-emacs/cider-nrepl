(ns cider.nrepl.middleware.test-test
  (:require
   [cider.nrepl.middleware.test :as test]
   [cider.nrepl.test-session :as session]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [matcher-combinators.clj-test])
  (:import
   (clojure.lang ExceptionInfo)))

;; Ensure tested tests are loaded:
(require 'cider.nrepl.middleware.test-filter-tests)

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
    (let [orig-fn cider.nrepl.middleware.test/report-fixture-error]
      (with-redefs [cider.nrepl.middleware.test/report-fixture-error
                    (fn [ns ^Throwable e]
                      (when-not (= (:data (ex-data e)) 42)
                        ;; Caught wrong exception here, print stacktrace to
                        ;; assist in debugging this.
                        (.printStackTrace e))
                      (orig-fn ns e))]
        (let [{{{[{:keys [error]}] :cider.nrepl.middleware.test/unknown} :cider.nrepl.middleware.test-with-throwing-fixtures} :results
               :keys [summary status]
               :as test-result}
              (session/message {:op "test"
                                :ns "cider.nrepl.middleware.test-with-throwing-fixtures"})]
          (testing (pr-str test-result)
            (is (= "clojure.lang.ExceptionInfo: I'm an exception inside a fixture! {:data 42}"
                   error))
            (is (= {:error 1, :fail 0, :ns 1, :pass 0, :test 0, :var 0}
                   summary))
            (is (= #{"done"} status))))))))

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

(deftest elapsed-time-test
  (require 'failing-test-ns)
  (require 'failing-test-ns-2)
  (let [test-result (session/message {:op "test-var-query"
                                      :var-query {:ns-query {:exactly ["failing-test-ns"]}}})
        [[var1 elapsed-time1]
         [var2 elapsed-time2]]
        (->> test-result
             :results
             :failing-test-ns
             ((juxt :fast-failing-test :slow-failing-test))
             (map (fn [[{:keys [var]
                         {:keys [ms]} :elapsed-time}]]
                    [var ms])))]
    (is (= "fast-failing-test" var1))
    (is (< elapsed-time1 (if (System/getenv "CI")
                           200
                           50))
        "Reports the elapsed time under [:elapsed-time :ms], as integer.
The low numberic value reflects that it times things correctly for a fast test.")

    (is (= "slow-failing-test" var2))
    (is (> elapsed-time2 998)
        "Reports the elapsed time under [:elapsed-time :ms], as integer.
The `988` value reflects that it times things correctly for a slow test.")

    (let [{:keys [humanized ms]} (-> test-result :ns-elapsed-time :failing-test-ns)]
      (is (> ms 998)
          "Reports the elapsed time for the entire ns")
      (is (= (str "Completed in " ms " ms")
             humanized)))

    (let [{:keys [humanized ms]} (-> test-result :elapsed-time)]
      (is (> ms 998)
          "Reports the elapsed time for the entire run, across namespaces")
      (is (= (str "Completed in " ms " ms")
             humanized))))

  (let [test-result (session/message {:op "retest"})]
    (is (string? (:humanized (:elapsed-time test-result)))
        "Timing also works for the `retest` op (global level)")
    (is (-> test-result :ns-elapsed-time :failing-test-ns :humanized string?)
        "Timing also works for the `retest` op (ns level)")
    (is (-> test-result :results :failing-test-ns :fast-failing-test (get 0) :elapsed-time :humanized string?)
        "Timing also works for the `retest` op (var level)"))

  (testing "Tests with multiple testing contexts"
    (let [test-result (session/message {:op "test-var-query"
                                        :var-query {:ns-query {:exactly ["failing-test-ns2"]}}})
          vars1 (-> test-result :results :failing-test-ns2 :two-clauses)
          vars2 (-> test-result :results :failing-test-ns2 :uses-are)]
      (assert (-> vars1 count #{2})
              "There's a test with two testing contexts")
      (assert (-> vars2 count #{2})
              "There's a test with two testing contexts")
      (assert (every? (fn [m]
                        (contains? m :expected))
                      vars1))
      (assert (every? (fn [m]
                        (contains? m :expected))
                      vars2))
      (is (not-any? (fn [m]
                      (contains? m :elapsed-time))
                    vars1)
          "If a deftest contains two testing contexts, :elapsed-time will be absent")
      (is (not-any? (fn [m]
                      (contains? m :elapsed-time))
                    vars2)
          "If a deftest contains two testing contexts, :elapsed-time will be absent")
      (is (-> test-result :var-elapsed-time :failing-test-ns2 :two-clauses :elapsed-time)
          "Timing info is, however, available at the var level")
      (is (-> test-result :var-elapsed-time :failing-test-ns2 :uses-are :elapsed-time)
          "Timing info is, however, available at the var level"))))

(deftest fail-fast-test
  (require 'failing-test-ns)
  (let [test-result (session/message {:op "test-var-query"
                                      :var-query {:ns-query {:exactly ["failing-test-ns"]}}
                                      :fail-fast "true"})]
    (is (= 1
           (count (:failing-test-ns (:results test-result))))))

  (let [test-result (session/message {:op "test-var-query"
                                      :var-query {:ns-query {:exactly ["failing-test-ns"]}}
                                      :fail-fast "false"})]
    (is (= 2
           (count (:failing-test-ns (:results test-result))))))

  (let [test-result (session/message {:op "retest"
                                      :fail-fast "false"})]
    (is (= 2
           (count (:failing-test-ns (:results test-result))))))

  (let [test-result (session/message {:op "retest"
                                      :fail-fast "true"})]
    (is (= 1
           (count (:failing-test-ns (:results test-result)))))))

(deftest print-object-test
  (testing "uses println for matcher-combinators results, otherwise invokes pprint"
    (is (= "{no quotes}\n"
           (#'test/print-object (matcher-combinators.clj-test/tagged-for-pretty-printing
                                 '(not (match? 1 2))
                                 {:matcher-combinators.result/value {"no" "quotes"}})))
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
