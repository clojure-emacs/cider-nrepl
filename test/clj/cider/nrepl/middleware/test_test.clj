(ns cider.nrepl.middleware.test-test
  (:require
   [cider.nrepl.middleware.test :as test]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [matcher-combinators.clj-test]
   [matcher-combinators.matchers :as matchers]
   [orchard.test]))

;; Ensure tested tests are loaded:
(require 'cider.nrepl.middleware.test-filter-tests)
(require 'cider.nrepl.middleware.test-report-counters-tests)
(require 'cider.nrepl.middleware.test-ns-hook-tests)

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
                        {:op "cider/test"
                         :ns "cider.nrepl.middleware.test-filter-tests"
                         :tests (map name tests)})]
                   (= tests (keys (:cider.nrepl.middleware.test-filter-tests results))))
      [:a-puff-of-smoke-test]
      [:a-smokey-test]
      [:a-puff-of-smoke-test :a-smokey-test]
      [:a-puff-of-smoke-test :a-smokey-test :yet-an-other-test])))

(deftest test-ns-hook-test
  ;; #680: a namespace whose assertions run only through `test-ns-hook` (with no
  ;; standalone `deftest` vars for the var query to find) must still be
  ;; exercised, instead of being silently skipped as "No assertions were run".
  (testing "the test op honors test-ns-hook for a hook-only namespace"
    (is+ {:summary {:test 1 :pass 1 :fail 0 :error 0}}
         (session/message {:op "test"
                           :ns "cider.nrepl.middleware.test-ns-hook-tests"}))))

(deftest only-smoke-test-run-test-deprecated
  (testing "only test marked as smoke is run when test-all is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op      "cider/test-all"
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
         (:results (session/message {:op "cider/test"
                                     :ns "cider.nrepl.middleware.test-filter-tests"})))))

(deftest only-smoke-test-run-test
  (testing "only test marked as smoke is run when test-var-query is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op "cider/test-var-query"
                                     :var-query {:include-meta-key ["smoke"]
                                                 :exclude-meta-key ["integration"]}}))))

  (testing "only test marked as smoke is run when test-ns is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op "cider/test-var-query"
                                     :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}
                                                 :include-meta-key ["smoke"]
                                                 :exclude-meta-key ["integration"]}}))))

  (testing "only test not marked as integration is run when test-ns is used"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?
                                                     :yet-an-other-test some?
                                                     :test-with-map-as-message some?}}
         (:results (session/message {:op "cider/test-var-query"
                                     :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}
                                                 :exclude-meta-key ["integration"]}}))))

  (testing "marked test is still run if filter is not used"
    (is+ {:cider.nrepl.middleware.test-filter-tests
          (matchers/all-of {:a-puff-of-smoke-test some?}
                           #(> (count %) 1))}
         (:results (session/message {:op "cider/test-var-query"
                                     :var-query {:ns-query {:exactly ["cider.nrepl.middleware.test-filter-tests"]}}})))))

(deftest handling-of-tests-with-throwing-fixtures
  (require 'cider.nrepl.middleware.test-with-throwing-fixtures)
  (testing "If a given deftest's fixture throw an exception, those are gracefully handled"
    (let [orig-fn orchard.test/report-fixture-error]
      (with-redefs [orchard.test/report-fixture-error
                    (fn [ns ^Throwable e]
                      (when-not (= (:data (ex-data e)) 42)
                        ;; Caught wrong exception here, print stacktrace to
                        ;; assist in debugging this.
                        (.printStackTrace e))
                      (orig-fn ns e))]
        (is+ {:status #{"done"}
              :summary {:error 1, :fail 0, :ns 1, :pass 0, :test 0, :var 0}
              :results {:cider.nrepl.middleware.test-with-throwing-fixtures
                        {:orchard.test/unknown
                         [{:error "clojure.lang.ExceptionInfo: I'm an exception inside a fixture! {:data 42}"}]}}}
             (session/message {:op "cider/test"
                               :ns "cider.nrepl.middleware.test-with-throwing-fixtures"}))))))

(deftest run-test-with-map-as-documentation-message
  (testing "documentation message map is returned as string"
    (is+ {:results
          {:cider.nrepl.middleware.test-filter-tests
           {:test-with-map-as-message [{:message "{:key \"val\"}"}]}}}
         (session/message {:op "cider/test"
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
       (session/message {:op "cider/test-var-query"
                         :var-query {:ns-query {:exactly ["failing-test-ns"]}}}))

  (testing "Timing also works for `retest` on all levels"
    (is+ {:elapsed-time {:humanized string?}
          :ns-elapsed-time {:failing-test-ns {:humanized string?}}
          :results {:failing-test-ns
                    {:fast-failing-test [{:elapsed-time {:humanized string?}}]}}}
         (session/message {:op "cider/retest"})))

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
         (session/message {:op "cider/test-var-query"
                           :var-query {:ns-query {:exactly ["failing-test-ns2"]}}}))))

(deftest fail-fast-test
  (require 'failing-test-ns)
  (let [test-result (session/message {:op "cider/test-var-query"
                                      :var-query {:ns-query {:exactly ["failing-test-ns"]}}
                                      :fail-fast "true"})]
    (is (= 1
           (count (:failing-test-ns (:results test-result))))))

  (let [test-result (session/message {:op "cider/test-var-query"
                                      :var-query {:ns-query {:exactly ["failing-test-ns"]}}
                                      :fail-fast "false"})]
    (is (= 2
           (count (:failing-test-ns (:results test-result))))))

  (let [test-result (session/message {:op "cider/retest"
                                      :fail-fast "false"})]
    (is (= 2
           (count (:failing-test-ns (:results test-result))))))

  (let [test-result (session/message {:op "cider/retest"
                                      :fail-fast "true"})]
    (is (= 1
           (count (:failing-test-ns (:results test-result)))))))

(deftest report-counters-bound-test
  (testing "*report-counters* is bound during test execution (see #686)"
    (is+ {:summary {:pass 1, :fail 0, :error 0}}
         (session/message {:op "cider/test"
                           :ns "cider.nrepl.middleware.test-report-counters-tests"
                           :tests ["report-counters-bound-test"]}))))

(deftest deprecated-ops-test
  (testing "Deprecated 'test' op still works"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op      "test"
                                     :ns      "cider.nrepl.middleware.test-filter-tests"
                                     :include ["smoke"]
                                     :exclude ["integration"]}))))

  (testing "Deprecated 'test-all' op still works"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op      "test-all"
                                     :include ["smoke"]
                                     :exclude ["integration"]}))))

  (testing "Deprecated 'test-var-query' op still works"
    (is+ {:cider.nrepl.middleware.test-filter-tests {:a-puff-of-smoke-test some?}}
         (:results (session/message {:op "test-var-query"
                                     :var-query {:include-meta-key ["smoke"]
                                                 :exclude-meta-key ["integration"]}}))))

  (testing "Deprecated 'retest' op still works"
    (require 'failing-test-ns)
    (session/message {:op "cider/test-var-query"
                      :var-query {:ns-query {:exactly ["failing-test-ns"]}}})
    (is+ {:results {:failing-test-ns some?}}
         (session/message {:op "retest"}))))

;; This spec exercises the deprecated vars on purpose.
#_{:clj-kondo/ignore [:deprecated-var]}
(deftest deprecated-runner-vars-test
  (testing "code extending the old `report` multimethod extends the one in use"
    (is (identical? orchard.test/report test/report)))
  (testing "the old entry points still run tests"
    (require 'failing-test-ns)
    (is+ {:summary {:fail 1}}
         (test/test-nss {'failing-test-ns ['fast-failing-test]}))))

(deftest test-error-handler-test
  (testing "errors are passed to `*test-error-handler*`"
    (require 'cider.nrepl.middleware.test-with-throwing-fixtures)
    (let [proof (atom [])
          orig test/*test-error-handler*]
      (alter-var-root #'test/*test-error-handler* (constantly #(swap! proof conj %)))
      (try
        (session/message {:op "cider/test"
                          :ns "cider.nrepl.middleware.test-with-throwing-fixtures"})
        (finally
          (alter-var-root #'test/*test-error-handler* (constantly orig))))
      (is (= [42] (map (comp :data ex-data) @proof))))))

(deftest stream-test
  (require 'failing-test-ns)
  (let [request {:op "cider/test-var-query"
                 :var-query {:ns-query {:exactly ["failing-test-ns"]}}
                 :fail-fast "false"}
        events (fn [responses] (keep :test-event responses))]
    (testing "progress is streamed ahead of the report when asked for"
      (let [responses (session/message (assoc request :stream "true") false)
            evts (events responses)]
        (is+ [{:type "begin-ns" :ns "failing-test-ns"}
              {:type "end-var" :ns "failing-test-ns"
               :summary {:fail pos?}
               :results [{:type "fail" :expected string?}]}
              {:type "end-var" :results [{:type "fail"}]}
              {:type "end-ns" :ns "failing-test-ns" :elapsed-time {:ms int?}}]
             evts)
        (testing "before the final report"
          (is (< (.indexOf ^java.util.List responses (last (filter :test-event responses)))
                 (.indexOf ^java.util.List responses (first (filter :results responses))))))))
    (testing "nothing is streamed otherwise"
      (is (empty? (events (session/message request false)))))))
