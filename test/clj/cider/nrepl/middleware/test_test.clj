(ns cider.nrepl.middleware.test-test
  (:require
   [cider.nrepl.middleware.test :as test]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [matcher-combinators.clj-test]
   [matcher-combinators.matchers :as matchers])
  (:import
   (clojure.lang ExceptionInfo)))

;; Ensure tested tests are loaded:
(require 'cider.nrepl.middleware.test-filter-tests)

(use-fixtures :each session/session-fixture)

(deftest basic-sanity-test
  ;; Just make sure that the namespace loads properly and the macro
  ;; expands without errors. (See #264)
  (is (seq (meta #'test/handle-test))))

;; NB! Most tests in this namespace CANNOT be properly run with cider-test
;; itself because the internal testing results overwrite the outer results! I've
;; spent a lot of time figuring this out, don't repeat my mistake. Just use
;; out-of-process testing (lein test) to validate this namespace.

(deftest only-selected-tests
  (testing "only single test is run with test"
    (are [tests] (let [{:keys [results] :as test-result}
                       (session/message
                        {:op "test"
                         :ns "cider.nrepl.middleware.test-filter-tests"
                         :tests (map name tests)})]
                   (= tests (keys (:cider.nrepl.middleware.test-filter-tests results))))
      [:a-puff-of-smoke-test]
      [:a-smokey-test]
      [:a-puff-of-smoke-test :a-smokey-test]
      [:a-puff-of-smoke-test :a-smokey-test :yet-an-other-test])))

(deftest only-smoke-test-run-test-deprecated
  (testing "only test marked as smoke is run when test-all is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op      "test-all"
                                     :include ["smoke"]
                                     :exclude ["integration"]}))))

  (testing "only test marked as smoke is run when test-ns is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op      "test"
                                     :ns      "cider.nrepl.middleware.test-filter-tests"
                                     :include ["smoke"]
                                     :exclude ["integration"]}))))

  (testing "only test not marked as integration is run when test-ns is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?
                                                     :yet-an-other-test some?
                                                     :test-with-map-as-message some?}}
         (:results (session/message {:op      "test"
                                     :ns      "cider.nrepl.middleware.test-filter-tests"
                                     :exclude ["integration"]}))))

  (testing "marked test is still run if filter is not used"
    (is+ {:cider.nrepl.middleware.test-filter-tests
          (matchers/all-of {:a-puff-of-smoke-test some?}
                           #(> (count %) 1))}
         (:results (session/message {:op "test"
                                     :ns "cider.nrepl.middleware.test-filter-tests"})))))

(deftest only-smoke-test-run-test
  (testing "only test marked as smoke is run when test-var-query is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op "test-var-query"
                                     :var-query {:include-meta-key ["smoke"]
                                                 :exclude-meta-key ["integration"]}}))))

  (testing "only test marked as smoke is run when test-ns is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op "test-var-query"
                                     :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}
                                                 :include-meta-key ["smoke"]
                                                 :exclude-meta-key ["integration"]}}))))

  (testing "only test not marked as integration is run when test-ns is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?
                                                     :yet-an-other-test some?
                                                     :test-with-map-as-message some?}}
         (:results (session/message {:op "test-var-query"
                                     :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}
                                                 :exclude-meta-key ["integration"]}}))))

  (testing "marked test is still run if filter is not used"
    (is+ {:cider.nrepl.middleware.test-filter-tests
          (matchers/all-of {:a-puff-of-smoke-test some?}
                           #(> (count %) 1))}
         (:results (session/message {:op "test-var-query"
                                     :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}}})))))

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
        (is+ {:status #{"done"}
              :summary {:error 1, :fail 0, :ns 1, :pass 0, :test 0, :var 0}
              :results {:cider.nrepl.middleware.test-with-throwing-fixtures
                        {:cider.nrepl.middleware.test/unknown
                         [{:error "clojure.lang.ExceptionInfo: I'm an exception inside a fixture! {:data 42}"}]}}}
             (session/message {:op "test"
                               :ns "cider.nrepl.middleware.test-with-throwing-fixtures"}))))))

(deftest run-test-with-map-as-documentation-message
  (testing "documentation message map is returned as string"
    (is+ {:results
          {:cider.nrepl.middleware.test-filter-tests
           {:test-with-map-as-message [{:message "{:key \"val\"}"}]}}}
         (session/message {:op "test"
                           :ns "cider.nrepl.middleware.test-filter-tests"
                           :tests ["test-with-map-as-message"]}))))

(deftest elapsed-time-test
  (require 'failing-test-ns)
  (require 'failing-test-ns-2)
  (is+ {:results {:failing-test-ns
                  {:fast-failing-test [{:var "fast-failing-test"
                                        ;; Should finish quickly
                                        :elapsed-time {:ms #(< % 200)}}]
                   :slow-failing-test [{:var "slow-failing-test"
                                        ;; Should finish slowly
                                        :elapsed-time {:ms #(> % 950)}}]}}
        :ns-elapsed-time {:failing-test-ns
                          {:humanized #"Completed in \d+ ms"
                           ;; Reports the elapsed time for the entire ns
                           :ms #(> % 950)}}
        :elapsed-time {:humanized #"Completed in \d+ ms"
                       ;; Reports the elapsed time for the entire run,
                       ;; across namespaces
                       :ms #(> % 950)}}
       (session/message {:op "test-var-query"
                         :var-query {:ns-query {:exactly ["failing-test-ns"]}}}))

  (testing "Timing also works for `retest` on all levels"
    (is+ {:elapsed-time {:humanized string?}
          :ns-elapsed-time {:failing-test-ns {:humanized string?}}
          :results {:failing-test-ns
                    {:fast-failing-test [{:elapsed-time {:humanized string?}}]}}}
         (session/message {:op "retest"})))

  (testing "Tests with multiple testing contexts"
    (is+ {:results {:failing-test-ns2
                    {:two-clauses
                     [{:expected some?
                       ;; If a deftest contains two testing
                       ;; contexts, :elapsed-time will be absent
                       :elapsed-time matchers/absent}
                      {:expected some?
                       :elapsed-time matchers/absent}]
                     :uses-are
                     [{:expected some?
                       :elapsed-time matchers/absent}
                      {:expected some?
                       :elapsed-time matchers/absent}]}}
          ;; Timing info is, however, available at the var level.
          :var-elapsed-time {:failing-test-ns2
                             {:two-clauses {:elapsed-time some?}
                              :uses-are {:elapsed-time some?}}}}
         (session/message {:op "test-var-query"
                           :var-query {:ns-query {:exactly ["failing-test-ns2"]}}}))))

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

(deftest stack-frame-test
  (let [e (try
            (throws)
            (catch ExceptionInfo e
              e))]
    (is+ {:fn "throws"
          :method "invokeStatic"
          :ns "cider.nrepl.middleware.test-test"
          :name "cider.nrepl.middleware.test_test$throws/invokeStatic"
          :file "test_test.clj"
          :type :clj
          :var "cider.nrepl.middleware.test-test/throws"
          :class "cider.nrepl.middleware.test_test$throws"
          :flags #{:project :clj :tooling}}
         (test/stack-frame e throws)
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
