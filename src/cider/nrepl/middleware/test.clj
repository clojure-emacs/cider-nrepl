(ns cider.nrepl.middleware.test
  "Test execution, reporting, and inspection"
  {:author "Jeff Valk"}
  (:require [cider.nrepl.middleware.stacktrace :as st]
            [cider.nrepl.middleware.util.misc :as u]
            [clojure.pprint :as pp]
            [clojure.test :as test]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.pr-values :refer [pr-values]]
            [clojure.tools.nrepl.middleware.session :refer [session]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as t]))

;;; ## Overview
;; This middleware provides test execution and reporting support for the
;; `clojure.test` machinery. In this model, the smallest unit of execution is
;; the var (as created by `deftest`); which runs all the tests defined within
;; that function.
;;
;; Report results are stored in the session, indexed by namespace, var, and
;; assertion index within the var. This enables individual test failures/errors
;; to be inspected on demand, including cause and stacktrace detail for errors.
;;
;; Non-passing tests may be re-run for any namespace using the `retest` op.
;; Whenever a var's tests are run, their previous results are overwritten, so
;; the session always holds the most recent test result for each var.


;;; ## Test Results
;; `clojure.test` allows extensible test reporting by rebinding the `report`
;; function. The implementation below does this to capture report events in the
;; `current-report` atom.

(def current-report
  "An atom holding the results of the test run in progress"
  (atom nil))

(defn report-reset! []
  (reset! current-report {:summary {:var 0 :test 0 :pass 0 :fail 0 :error 0}
                          :results {} :ns nil}))

;; This searches the stacktrace using the var's `:test` metadata (which holds
;; the actual test fn). This is similar to the `clojure.test/file-position`
;; approach, but resolves the correct frame when the error occurs outside of an
;; `is` form.
(defn error-line
  "Given metadata for a test var and an exception thrown while running the test,
  return the line number of the erring assertion."
  [v e]
  (let [c (-> v :test class .getName)]
    (->> (map st/analyze-frame (.getStackTrace e))
         (filter #(= c (:class %)))
         (first)
         :line)))

(defn test-result
  "Transform the result of a test assertion. Append ns, var, assertion index,
  and 'testing' context. Retain any exception. Pretty-print expected/actual."
  [ns v m]
  (let [c (when test/*testing-contexts* (test/testing-contexts-str))
        i (count (-> @current-report :results (:name v)))]
    (merge {:ns ns, :var (:name v), :index i, :context c}
           (if (#{:fail :error} (:type m))
             (-> (if-let [e (when (= :error (:type m)) (:actual m))]
                   (assoc m :error e, :line (error-line v e))
                   m)
                 (update-in [:expected] #(with-out-str (pp/pprint %)))
                 (update-in [:actual]   #(with-out-str (pp/pprint %))))
             (dissoc m :expected :actual)))))

(defn report
  "Handle reporting for test events. This takes a test event map as an argument
  and updates the `current-report` atom to reflect test results and summary
  statistics."
  [m]
  (let [ns (:ns @current-report)
        v  (-> test/*testing-vars* last meta)
        update! (partial swap! current-report update-in)]
    (condp get (:type m)
      #{:begin-test-ns}     (do (update! [:ns] (constantly (ns-name (:ns m)))))
      #{:begin-test-var}    (do (update! [:summary :var] inc))
      #{:pass :fail :error} (do (update! [:summary :test] inc)
                                (update! [:summary (:type m)] inc)
                                (update! [:results (:name v)]
                                         (fnil conj []) (test-result ns v m)))
      nil)))


;;; ## Test Execution
;; These functions are based on the ones in `clojure.test`, updated to accept
;; a list of vars to test, and use the report implementation above.

(defn test-vars
  "Call `clojure.test/test-var` on each var, with the fixtures defined for
  namespace object `ns`."
  [ns vars]
  (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta ns)))
        each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta ns)))]
    (once-fixture-fn
     (fn []
       (doseq [v vars]
         (when (:test (meta v))
           (each-fixture-fn (fn [] (test/test-var v)))))))))

(defn test-ns
  "If the namespace object defines a function named `test-ns-hook`, call that.
  Otherwise, test the specified vars. If no vars are specified, test all vars
  in the namespace. On completion, return a map of test results."
  [ns vars]
  (binding [test/report report]
    (report-reset!)
    (test/do-report {:type :begin-test-ns, :ns ns})
    (if-let [test-hook (ns-resolve ns 'test-ns-hook)]
      (test-hook)
      (test-vars ns (or (seq vars)
                        (vals (ns-interns ns)))))
    (test/do-report {:type :end-test-ns, :ns ns})
    @current-report))


;;; ## Middleware

(def results
  "An atom holding results of test runs, indexed by namespace. This is used to
  reference exception objects from erring tests, and to rerun tests (by
  namespace) that did not pass previously. The var itself will be bound from
  the nREPL session."
  (atom {}))

(defn handle-test
  "Run tests in the specified namespace and return results. This accepts a set
  of `tests` to be run; if nil, runs all tests. Results are cached for exception
  retrieval and to enable re-running of failed/erring tests."
  [{:keys [ns tests session transport] :as msg}]
  (with-bindings @session
    (if-let [ns (try (doto (symbol ns) require) (catch Exception _))]
      (let [report (->> (map #(ns-resolve ns (symbol %)) tests)
                        (filter identity)
                        (test-ns (the-ns ns)))]
        (swap! results update-in [ns] merge (:results report))
        (t/send transport (response-for msg (u/transform-value report))))
      (t/send transport (response-for msg :status :namespace-not-found)))
    (t/send transport (response-for msg :status :done))))

(defn handle-stacktrace
  "Return exception cause and stack frame info for an erring test via the
  `stacktrace` middleware. The error to be retrieved is referenced by namespace,
  var name, and assertion index within the var."
  [{:keys [ns var index session transport] :as msg}]
  (with-bindings @session
    (let [[ns var] (map u/as-sym [ns var])]
      (if-let [e (get-in @results [ns var index :error])]
        (doseq [cause (st/analyze-causes e)]
          (t/send transport (response-for msg cause)))
        (t/send transport (response-for msg :status :no-error)))
      (t/send transport (response-for msg :status :done)))))

(defn handle-retest
  "Rerun tests in the specified namespace that did not pass when last run. This
  behaves exactly as the `test` op, but passes the set of `tests` having
  previous failures/errors."
  [{:keys [ns session] :as msg}]
  (with-bindings @session
    (let [problems (->> (mapcat val (get @results (u/as-sym ns)))
                        (filter (comp #{:fail :error} :type)))
          retests (distinct (map :var problems))]
      (handle-test (assoc msg :tests retests)))))

(defn wrap-test
  "Middleware that handles testing requests"
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "test"            (handle-test msg)
      "test-stacktrace" (handle-stacktrace msg)
      "retest"          (handle-retest msg)
      (handler msg))))

;; nREPL middleware descriptor info
(set-descriptor!
 #'wrap-test
 {:requires #{#'session}
  :expects #{#'pr-values}
  :handles {"test"            {:doc (:doc (meta #'handle-test))}
            "test-stacktrace" {:doc (:doc (meta #'handle-stacktrace))}
            "retest"          {:doc (:doc (meta #'handle-retest))}}})
