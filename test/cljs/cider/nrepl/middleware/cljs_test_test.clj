(ns cider.nrepl.middleware.cljs-test-test
  (:require
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]))

(use-fixtures :once piggieback-fixture)

(def ^:private sample-ns "cider.nrepl.sample-cljs-test-ns")

(defn- require-sample! []
  (session/message {:op "eval"
                    :code (nrepl/code (require 'cider.nrepl.sample-cljs-test-ns))}))

(deftest cljs-test-var-query-test
  (require-sample!)
  (let [{:keys [summary results status]}
        (session/message {:op "cider/test-var-query"
                          :var-query {:ns-query {:exactly [sample-ns]}}})
        ns-results (:cider.nrepl.sample-cljs-test-ns results)]
    (is (= #{"done"} status))
    (testing "summary counts"
      (is (= 1 (:ns summary)))
      (is (= 3 (:var summary)))
      (is (= 3 (:test summary)))
      (is (= 1 (:pass summary)))
      (is (= 1 (:fail summary)))
      (is (= 1 (:error summary))))
    (testing "per-var results"
      (is (= #{:passing-test :failing-test :erroring-test}
             (set (keys ns-results))))
      (is (= "pass" (:type (first (:passing-test ns-results)))))
      (is (= "fail" (:type (first (:failing-test ns-results)))))
      (is (= "error" (:type (first (:erroring-test ns-results))))))
    (testing "testing context is captured"
      (is (= "a passing assertion" (:context (first (:passing-test ns-results))))))))

(deftest cljs-test-at-point-test
  (require-sample!)
  (let [{:keys [summary results]}
        (session/message {:op "cider/test"
                          :ns sample-ns
                          :tests ["passing-test"]})]
    (testing "only the requested var is reported"
      (is (= 1 (:test summary)))
      (is (= 1 (:pass summary)))
      (is (= #{:passing-test}
             (set (keys (:cider.nrepl.sample-cljs-test-ns results))))))))

(deftest cljs-retest-test
  (require-sample!)
  (session/message {:op "cider/test-var-query"
                    :var-query {:ns-query {:exactly [sample-ns]}}})
  (let [{:keys [summary results]} (session/message {:op "cider/retest"})]
    (testing "only previously-failing vars are re-run"
      (is (= 2 (:test summary)))
      (is (= 0 (:pass summary)))
      (is (= 1 (:fail summary)))
      (is (= 1 (:error summary)))
      (is (= #{:failing-test :erroring-test}
             (set (keys (:cider.nrepl.sample-cljs-test-ns results))))))))

(deftest cljs-retest-no-failures-test
  (require-sample!)
  ;; A run with no failures, then retest: must complete (send `done`) rather
  ;; than hang, and report nothing to re-run.
  (session/message {:op "cider/test" :ns sample-ns :tests ["passing-test"]})
  (let [{:keys [summary status]} (session/message {:op "cider/retest"})]
    (is (= #{"done"} status))
    (is (= 0 (:test summary)))))

(deftest cljs-async-test
  ;; cljs.test/async tests finish after run-tests returns; the middleware must
  ;; poll the runtime until they complete rather than reporting empty results.
  (session/message {:op "eval"
                    :code (nrepl/code (require 'cider.nrepl.sample-cljs-async-test-ns))})
  (let [{:keys [summary results status]}
        (session/message {:op "cider/test-var-query"
                          :var-query {:ns-query {:exactly ["cider.nrepl.sample-cljs-async-test-ns"]}}})]
    (is (= #{"done"} status))
    (testing "async assertions are awaited and captured"
      (is (= 2 (:test summary)))
      (is (= 1 (:pass summary)))
      (is (= 1 (:fail summary)))
      (is (= #{:async-passing-test :async-failing-test}
             (set (keys (:cider.nrepl.sample-cljs-async-test-ns results))))))))

(deftest cljs-async-timeout-test
  (session/message {:op "eval"
                    :code (nrepl/code (require 'cider.nrepl.sample-cljs-timeout-test-ns))})
  (let [{:keys [status]}
        (session/message {:op "cider/test-var-query"
                          :var-query {:ns-query {:exactly ["cider.nrepl.sample-cljs-timeout-test-ns"]}}
                          :timeout "800"})]
    (testing "an async test that never completes times out instead of hanging"
      (is (contains? status "test-timeout"))
      (is (contains? status "done")))))

(deftest cljs-namespace-not-found-test
  (require-sample!)
  (let [{:keys [status]} (session/message {:op "cider/test-var-query"
                                           :var-query {:ns-query {:exactly ["no.such.ns"]}}})]
    (is (contains? status "namespace-not-found"))
    (is (contains? status "done"))))
