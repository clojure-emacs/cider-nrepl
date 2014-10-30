(ns cider.nrepl.middleware.test
  "Test execution, reporting, and inspection"
  {:author "Jeff Valk"}
  (:require [cider.nrepl.middleware.stacktrace :as st]
            [cider.nrepl.middleware.util.misc :as u]
            [clojure.pprint :as pp]
            [clojure.test :as test]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
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

;; In the case of test errors, line number is obtained by searching the
;; stacktrace for the originating function. The search target will be the
;; current test var's `:test` metadata (which holds the actual test function) if
;; present, or the deref'ed var function otherwise (i.e. test fixture errors).
;;
;; This approach is similar in use to `clojure.test/file-position`, but doesn't
;; assume a fixed position in the stacktrace, and therefore resolves the correct
;; frame when the error occurs outside of an `is` form.

(defn stack-frame
  "Search the stacktrace of exception `e` for the function `f` and return info
  describing the stack frame, including var, class, and line."
  [^Exception e f]
  (->> (map st/analyze-frame (.getStackTrace e))
       (filter #(= (:class %) (.getName (class f))))
       (first)))

(defn test-result
  "Transform the result of a test assertion. Append ns, var, assertion index,
  and 'testing' context. Retain any exception. Pretty-print expected/actual."
  [ns v m]
  (let [c (when (seq test/*testing-contexts*) (test/testing-contexts-str))
        i (count (get (@current-report :results) (:name (meta v))))
        f (or (:test (meta v)) @v)] ; test fn or deref'ed fixture
    (merge {:ns ns, :var (:name (meta v)), :index i, :context c}
           (if (#{:fail :error} (:type m))
             (-> (if-let [e (when (= :error (:type m)) (:actual m))]
                   (assoc m :error e, :line (:line (stack-frame e f)))
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
        v  (last test/*testing-vars*)
        update! (partial swap! current-report update-in)]
    (condp get (:type m)
      #{:begin-test-ns}     (do (update! [:ns] (constantly (ns-name (:ns m)))))
      #{:begin-test-var}    (do (update! [:summary :var] inc))
      #{:pass :fail :error} (do (update! [:summary :test] inc)
                                (update! [:summary (:type m)] inc)
                                (update! [:results (:name (meta v))]
                                         (fnil conj []) (test-result ns v m)))
      nil)))

(defn report-fixture-error
  "Delegate reporting for test fixture errors to the `report` function. This
  finds the erring test fixture in the stacktrace and binds it as the current
  test var. Test count is decremented to indicate that no tests were run."
  [ns e]
  (let [frame (->> (concat (::clojure.test/once-fixtures (meta ns))
                           (::clojure.test/each-fixtures (meta ns)))
                   (map (partial stack-frame e))
                   (filter identity)
                   (first))
        fixture (resolve (symbol (:var frame)))]
    (swap! current-report update-in [:summary :test] dec)
    (binding [test/*testing-vars* (list fixture)]
      (report {:type :error, :expected nil, :actual e
               :message "Unhandled exception in test fixture"}))))


;;; ## Test Execution
;; These functions are based on the ones in `clojure.test`, updated to accept
;; a list of vars to test, and use the report implementation above.

(defn test-vars
  "Call `clojure.test/test-var` on each var, with the fixtures defined for
  namespace object `ns`."
  [ns vars]
  (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta ns)))
        each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta ns)))]
    (try (once-fixture-fn
          (fn []
            (doseq [v vars]
              (when (:test (meta v))
                (each-fixture-fn (fn [] (test/test-var v)))))))
         (catch Throwable e
           (report-fixture-error ns e)))))

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
  (binding [*msg* msg]
    (with-bindings @session
      (if-let [ns (try (doto (symbol ns) require) (catch Exception _))]
        (let [report (->> (map #(ns-resolve ns (symbol %)) tests)
                          (filter identity)
                          (test-ns (the-ns ns)))]
          (swap! results update-in [ns] merge (:results report))
          (t/send transport (response-for msg (u/transform-value report))))
        (t/send transport (response-for msg :status :namespace-not-found)))
      (t/send transport (response-for msg :status :done)))))

(defn handle-stacktrace
  "Return exception cause and stack frame info for an erring test via the
  `stacktrace` middleware. The error to be retrieved is referenced by namespace,
  var name, and assertion index within the var."
  [{:keys [ns var index session transport print-level] :as msg}]
  (binding [*msg* msg]
    (with-bindings @session
      (let [[ns var] (map u/as-sym [ns var])]
        (if-let [e (get-in @results [ns var index :error])]
          (doseq [cause (st/analyze-causes e print-level)]
            (t/send transport (response-for msg cause)))
          (t/send transport (response-for msg :status :no-error)))
        (t/send transport (response-for msg :status :done))))))

(defn handle-retest
  "Rerun tests in the specified namespace that did not pass when last run. This
  behaves exactly as the `test` op, but passes the set of `tests` having
  previous failures/errors."
  [{:keys [ns session] :as msg}]
  (binding [*msg* msg]
    (with-bindings @session
      (let [problems (->> (mapcat val (get @results (u/as-sym ns)))
                          (filter (comp #{:fail :error} :type)))
            retests (distinct (map :var problems))]
        (handle-test (assoc msg :tests retests))))))

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
