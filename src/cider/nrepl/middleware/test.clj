(ns cider.nrepl.middleware.test
  "Test execution, reporting, and inspection"
  {:author "Jeff Valk"}
  (:require
   [cider.nrepl.middleware.stacktrace :as st]
   [cider.nrepl.middleware.test.extensions :as extensions]
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.coerce :as util.coerce]
   [clojure.pprint :as pp]
   [clojure.test :as test]
   [clojure.walk :as walk]
   [nrepl.middleware.interruptible-eval :as ie]
   [nrepl.middleware.print :as print]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]
   [orchard.misc :as misc]
   [orchard.query :as query]))

;;; ## Overview
;;
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
;;
;; `clojure.test` allows extensible test reporting by rebinding the `report`
;; function. The implementation below does this to capture report events in the
;; `current-report` atom.

(def current-report
  "An atom holding the results of the test run in progress"
  (atom nil))

(defn report-reset! []
  (reset! current-report {:summary {:ns 0 :var 0 :test 0 :pass 0 :fail 0 :error 0}
                          :results {} :testing-ns nil :gen-input nil}))

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

(defn- custom-print-method? [o]
  (= (get-method print-method (:type (meta o)))
     (get-method print-method :default)))

(defn test-result
  "Transform the result of a test assertion. Append ns, var, assertion index,
  and 'testing' context. Retain any exception. Pretty-print expected/actual."
  [ns v m]
  (let [{:keys [actual diffs expected fault]
         t :type} m
        c (when (seq test/*testing-contexts*) (test/testing-contexts-str))
        i (count (get-in (@current-report :results) [ns (:name (meta v))]))
        gen-input (:gen-input @current-report)
        pprint-str #(with-out-str (if (custom-print-method? %) (pp/pprint %) (println %)))]
    ;; Errors outside assertions (faults) do not return an :expected value.
    ;; Type :fail returns :actual value. Type :error returns :error and :line.
    (merge (dissoc m :expected :actual)
           {:ns ns, :var (:name (meta v)), :index i, :context c}
           (when (and (#{:fail :error} t) (not fault))
             {:expected (pprint-str expected)})
           (when (and (#{:fail} t) gen-input)
             {:gen-input (pprint-str gen-input)})
           (when (#{:fail} t)
             {:actual (pprint-str actual)})
           (when diffs
             {:diffs (extensions/diffs-result diffs)})
           (when (#{:error} t)
             (let [e actual
                   f (or (:test (meta v)) @v)] ; test fn or deref'ed fixture
               {:error e
                :line (:line (stack-frame e f))})))))

;;; ## test.check integration
;;
;; `test.check` generates random test inputs for property testing. We make the
;; inputs part of the report by parsing the respective calls to `report`:
;; `test.chuck`'s `checking` creates events of type
;; `:com.gfredericks.test.chuck.clojure-test/shrunk` with the minimal failing
;; input as determined by `test.check`. `test.check`'s own `defspec` does report
;; minimal inputs in recent versions, but for compatibility we also parse events
;; of type `:clojure.test.check.clojure-test/shrinking`, which `defspec`
;; produces to report failing input before shrinking it.

(defmulti report
  "Handle reporting for test events.

  This takes a test event map as an argument and updates the `current-report`
  atom to reflect test results and summary statistics."
  :type)

(defmethod report :default [m])

(defmethod report :begin-test-ns
  [m]
  (let [ns (ns-name (get m :ns (:testing-ns @current-report)))]
    (swap! current-report
           #(-> %
                (assoc :testing-ns ns)
                (update-in [:summary :ns] inc)))))

(defmethod report :begin-test-var
  [m]
  (swap! current-report update-in [:summary :var] inc))

(defn- report-final-status
  [{:keys [type] :as m}]
  (let [ns (ns-name (get m :ns (:testing-ns @current-report)))
        v (last test/*testing-vars*)]
    (swap! current-report
           #(-> %
                (update-in [:summary :test] inc)
                (update-in [:summary type] (fnil inc 0))
                (update-in [:results ns (:name (meta v))]
                           (fnil conj [])
                           (test-result ns v m))
                (assoc :gen-input nil)))))

(defmethod report :pass
  [m]
  (report-final-status m))

(defmethod report :fail
  [m]
  (report-final-status m))

(defmethod report :error
  [m]
  (report-final-status m))

(defmethod report :com.gfredericks.test.chuck.clojure-test/shrunk
  [m]
  (swap! current-report assoc :gen-input (-> m :shrunk :smallest)))

(defn- report-shrinking
  [{:keys [clojure.test.check.clojure-test/params] :as m}]
  (swap! current-report assoc :gen-input params))

(defmethod report :clojure.test.check.clojure-test/shrinking
  [m]
  (report-shrinking m))

(defmethod report :clojure.test.check.clojure-test/shrunk
  [m]
  (report-shrinking m))

(defmethod report :matcher-combinators/mismatch
  [m]
  (report-final-status (assoc m
                              :type :fail
                              :actual (:markup m))))

(defn report-fixture-error
  "Delegate reporting for test fixture errors to the `report` function. This
  finds the erring test fixture in the stacktrace and binds it as the current
  test var. Test count is decremented to indicate that no tests were run."
  [ns e]
  (let [frame (->> (concat (:clojure.test/once-fixtures (meta ns))
                           (:clojure.test/each-fixtures (meta ns)))
                   (map (partial stack-frame e))
                   (filter identity)
                   (first))
        fixture (resolve (symbol (:var frame)))]
    (swap! current-report update-in [:summary :test] dec)
    (binding [test/*testing-vars* (list fixture)]
      (report {:type :error, :fault true, :expected nil, :actual e
               :message "Uncaught exception in test fixture"}))))

;;; ## Test Execution
;;
;; These functions are based on the ones in `clojure.test`, updated to accept
;; a list of vars to test, use the report implementation above, and distinguish
;; between test errors and faults outside of assertions.

(defn test-var
  "If var `v` has a function in its `:test` metadata, call that function,
  with `clojure.test/*testing-vars*` bound to append `v`."
  [v]
  (when-let [t (:test (meta v))]
    (binding [test/*testing-vars* (conj test/*testing-vars* v)]
      (test/do-report {:type :begin-test-var :var v})
      (test/inc-report-counter :test)
      (try (t)
           (catch Throwable e
             (test/do-report {:type :error, :fault true, :expected nil, :actual e
                              :message "Uncaught exception, not in assertion"})))
      (test/do-report {:type :end-test-var :var v}))))

(defn test-vars
  "Call `test-var` on each var, with the fixtures defined for namespace object
  `ns`."
  [ns vars]
  (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta ns)))
        each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta ns)))]
    (try (once-fixture-fn
          (fn []
            (doseq [v vars]
              (each-fixture-fn (fn [] (test-var v))))))
         (catch Throwable e
           (report-fixture-error ns e)))))

(defn test-ns
  "If the namespace object defines a function named `test-ns-hook`, call that.
  Otherwise, test the specified vars. On completion, return a map of test
  results."
  [ns vars]
  (binding [test/report report]
    (test/do-report {:type :begin-test-ns, :ns ns})
    (if-let [test-hook (ns-resolve ns 'test-ns-hook)]
      (test-hook)
      (test-vars ns vars))
    (test/do-report {:type :end-test-ns, :ns ns})
    @current-report))

(defn test-var-query
  "Call `test-ns` for each var found via var-query."
  [var-query]
  (report-reset!)
  (doseq [[ns vars] (group-by
                     (comp :ns meta)
                     (query/vars var-query))]
    (test-ns ns vars))
  @current-report)

(defn test-nss
  "Call `test-ns` for each entry in map `m`, in which keys are namespace
  symbols and values are var symbols to be tested in that namespace (or `nil`
  to test all vars). Symbols are first resolved to their corresponding
  objects."
  [m]
  (report-reset!)
  (doseq [[ns vars] m]
    (->> (map (partial ns-resolve ns) vars)
         (filter identity)
         (test-ns (the-ns ns))))
  @current-report)

;;; ## Middleware

(def results
  "An atom holding results of test runs, indexed by namespace. This is used to
  reference exception objects from erring tests, and to rerun tests (by
  namespace) that did not pass previously. The var itself will be bound from
  the nREPL session."
  (atom {}))

(defn handle-test-var-query-op
  [{:keys [var-query transport session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (with-bindings (assoc @session #'ie/*msg* msg)
              (try
                (let [stringify-msg (fn [report]
                                      (walk/postwalk (fn [x] (if (and (map? x)
                                                                      (contains? x :message))
                                                               (update x :message str)
                                                               x))
                                                     report))
                      report (-> var-query
                                 (assoc-in [:ns-query :has-tests?] true)
                                 (assoc :test? true)
                                 (util.coerce/var-query)
                                 test-var-query
                                 stringify-msg)]
                  (reset! results (:results report))
                  (t/send transport (response-for msg (util/transform-value report))))
                (catch clojure.lang.ExceptionInfo e
                  (let [d (ex-data e)]
                    (if (::util.coerce/id d)
                      (case (::util.coerce/id d)
                        :namespace-not-found (t/send transport (response-for msg :status :namespace-not-found)))
                      (throw e)))))))
          (fn []
            (t/send transport (response-for msg {:status :done}))))))

(defn handle-test-op
  [{:keys [ns tests include exclude] :as msg}]
  (handle-test-var-query-op
   (merge msg {:var-query {:ns-query {:exactly [ns]}
                           :include-meta-key include
                           :exclude-meta-key exclude
                           :exactly (map #(str ns "/" %) tests)}})))

(defn handle-test-all-op
  [{:keys [load? include exclude] :as msg}]
  (handle-test-var-query-op
   (merge msg {:var-query {:ns-query {:project? true
                                      :load-project-ns? load?}
                           :include-meta-key include
                           :exclude-meta-key exclude}})))

(defn handle-retest-op
  [{:keys [transport session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (with-bindings (assoc @session #'ie/*msg* msg)
              (let [nss (reduce (fn [ret [ns tests]]
                                  (let [problems (filter (comp #{:fail :error} :type)
                                                         (mapcat val tests))
                                        vars (distinct (map :var problems))]
                                    (if (seq vars) (assoc ret ns vars) ret)))
                                {} @results)
                    report (test-nss nss)]
                (reset! results (:results report))
                (t/send transport (response-for msg (util/transform-value report))))))
          (fn []
            (t/send transport (response-for msg :status :done))))))

(defn handle-stacktrace-op
  [{:keys [ns var index transport session id ::print/print-fn] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (with-bindings (assoc @session #'ie/*msg* msg)
              (let [[ns var] (map misc/as-sym [ns var])]
                (if-let [e (get-in @results [ns var index :error])]
                  (doseq [cause (st/analyze-causes e print-fn)]
                    (t/send transport (response-for msg cause)))
                  (t/send transport (response-for msg :status :no-error))))))
          (fn []
            (t/send transport (response-for msg :status :done))))))

(defn handle-test [handler msg & configuration]
  (case (:op msg)
    "test-var-query"  (handle-test-var-query-op msg)
    "test"            (handle-test-op msg)
    "test-all"        (handle-test-all-op msg)
    "test-stacktrace" (handle-stacktrace-op msg)
    "retest"          (handle-retest-op msg)
    (handler msg)))
