(ns cider.nrepl.middleware.test
  "Test execution, reporting, and inspection"
  {:author "Jeff Valk"}
  (:require
   [cider.nrepl.middleware.test.extensions :as extensions]
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.coerce :as util.coerce]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.test :as test]
   [clojure.walk :as walk]
   [nrepl.middleware.interruptible-eval :as ie]
   [nrepl.middleware.print :as print]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]
   [orchard.misc :as misc]
   [orchard.query :as query]
   [orchard.stacktrace :as stacktrace]))

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
  (reset! current-report {:summary {:ns 0
                                    :var 0
                                    :test 0
                                    :pass 0
                                    :fail 0
                                    :error 0}
                          :results {}
                          :testing-ns nil
                          :gen-input nil}))

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
  (when-let [class-name (some-> f class .getName)]
    (when-let [analyzed-trace (:stacktrace (first (stacktrace/analyze e)))]
      (some #(when-let [frame-cname (:class %)]
               (when (and (string? frame-cname)
                          (str/starts-with? frame-cname class-name))
                 %))
            analyzed-trace))))

(defn- print-object
  "Print `object` using println for matcher-combinators results and pprint
   otherwise. The matcher-combinators library uses a custom print-method
   which doesn't get picked up by pprint since it uses a different dispatch
   mechanism."
  [object]
  (let [matcher-combinators-result? (= (:type (meta object))
                                       :matcher-combinators.clj-test/mismatch)
        print-fn (if matcher-combinators-result?
                   println
                   pp/pprint)
        result (with-out-str (print-fn object))]
    ;; Replace extra newlines at the end, as sometimes returned by matchers-combinators:
    (str/replace result #"\n\n+$" "\n")))

(def ^:dynamic *test-error-handler*
  "A function you can override via `binding`, or safely via `alter-var-root`.
  On test `:error`s, the related Throwable be invoked as the sole argument
  passed to this var.
  For example, you can use this to add an additional `println`,
  for pretty-printing Spec failures. Remember to `flush` if doing so."
  identity)

(def ^:private fallback-var-name
  "The pseudo var name which will be used when no var name can be found
  for a given test report."
  ::unknown)

(defn test-result
  "Transform the result of a test assertion. Append ns, var, assertion index,
  and 'testing' context. Retain any exception. Pretty-print expected/actual or
  use its `print-method`, if applicable."
  [ns v m]
  (let [{:keys [actual diffs expected fault]
         t :type} m
        v-name (or (:name (meta v)) fallback-var-name)
        c (when (seq test/*testing-contexts*) (test/testing-contexts-str))
        i (count (get-in (:results @current-report {}) [ns v-name]))
        gen-input (:gen-input @current-report)]

    ;; Errors outside assertions (faults) do not return an :expected value.
    ;; Type :fail returns :actual value. Type :error returns :error and :line.
    (merge (dissoc m :expected :actual)
           {:ns ns, :var v-name, :index i, :context c}
           (when (and (#{:fail :error} t) (not fault))
             {:expected (print-object expected)})
           (when (and (#{:fail} t) gen-input)
             {:gen-input (print-object gen-input)})
           (when (#{:fail} t)
             {:actual (print-object actual)})
           (when diffs
             {:diffs (extensions/diffs-result diffs)})
           (when (#{:error} t)
             (let [e actual
                   f (or (:test (meta v)) (some-> v deref))] ; test fn or deref'ed fixture
               (*test-error-handler* e)
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

(defmethod report :default [_m])

(defmethod report :begin-test-ns
  [m]
  (let [ns (ns-name (get m :ns (:testing-ns @current-report)))]
    (swap! current-report
           #(-> %
                (assoc :testing-ns ns)
                (update-in [:summary :ns] inc)))))

(defmethod report :begin-test-var
  [_m]
  (swap! current-report update-in [:summary :var] inc))

(defn- report-final-status
  [{:keys [type] :as m}]
  (let [ns (ns-name (get m :ns (:testing-ns @current-report)))
        v (last test/*testing-vars*)]
    (swap! current-report
           #(-> %
                (update-in [:summary :test] inc)
                (update-in [:summary type] (fnil inc 0))
                (update-in [:results ns (or (:name (meta v))
                                            fallback-var-name)]
                           (fnil conj [])
                           (test-result ns v m))
                (assoc :gen-input nil)))))

(defmethod report :end-test-var
  [{:keys [var-elapsed-time]
    var-ref :var}]
  (let [n (or (some-> var-ref meta :ns ns-name)
              (:testing-ns @current-report))
        v (or (-> var-ref meta :name)
              fallback-var-name)
        contexts-count (-> @current-report
                           (get-in [:results n v])
                           count)]
    ;; Store the per-timing-context timing, when possible
    (when (= 1 contexts-count)
      ;; The timing info is only valid when the test var contained a single `is` assertion.
      ;; This is because timing works at var (`deftest`) granularity, not at `is` granularity.
      (swap! current-report
             assoc-in
             [:results n v 0 :elapsed-time]
             var-elapsed-time))

    ;; Store the per-var timing.
    ;; Note that cider-test.el does not currently report test failures on a per-var manner,
    ;; however this data could be useful in a future or for other clients.
    (swap! current-report
           assoc-in
           [:var-elapsed-time n v :elapsed-time]
           var-elapsed-time)))

(defmethod report :end-test-ns
  [{:keys [ns-ref ns-elapsed-time]}]
  (let [n (or (some-> ns-ref ns-name)
              (:testing-ns @current-report))]
    (swap! current-report
           assoc-in
           [:ns-elapsed-time n]
           ns-elapsed-time)))

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
  [{:keys [clojure.test.check.clojure-test/params]}]
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
                   (keep #(stack-frame e %))
                   (first))
        fixture (resolve (symbol (:var frame)))]
    (swap! current-report update-in [:summary :test] dec)
    (binding [test/*testing-vars* (list fixture)]
      (report {:type :error, :fault true, :expected nil, :actual e
               :message "Uncaught exception in test fixture"}))))

(defmacro ^:private timing
  "Executes `body`, reporting the time it took by persisting it to `time-atom`."
  {:style/indent 1}
  [time-atom & body]
  {:pre [(seq body)]}
  `(let [then# (System/currentTimeMillis)
         v# (do
              ~@body)
         took# (- (System/currentTimeMillis)
                  then#)]
     (reset! ~time-atom {:ms took#
                         :humanized (str "Completed in " took# " ms")})
     v#))

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
      (let [time-info (atom nil)
            result (timing time-info
                     (try
                       (t)
                       ::ok
                       (catch Throwable e
                         e)))
            report (if (= ::ok result)
                     {:type :end-test-var
                      :var v}
                     {:type :error
                      :fault true
                      :expected nil
                      :actual result
                      :message "Uncaught exception, not in assertion"})]
        (test/do-report (assoc report :var-elapsed-time @time-info))))))

(defn- current-test-run-failed? []
  (or (some-> @current-report :summary :fail pos?)
      (some-> @current-report :summary :error pos?)))

(defn test-vars
  "Call `test-var` on each var, with the fixtures defined for namespace object
  `ns`."
  ([ns vars]
   (test-vars ns vars false))

  ([ns vars fail-fast?]
   (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta ns)))
         each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta ns)))]
     (try
       (once-fixture-fn
        (fn []
          (reduce (fn [_ v]
                    (cond-> (each-fixture-fn (fn []
                                               (test-var v)))
                      (and fail-fast? (current-test-run-failed?))
                      reduced))
                  nil
                  vars)))
       (catch Throwable e
         (report-fixture-error ns e))))))

(defn test-ns
  "If the namespace object defines a function named `test-ns-hook`, call that.
  Otherwise, test the specified vars. On completion, return a map of test
  results."
  ([ns vars]
   (test-ns ns vars false))

  ([ns vars fail-fast?]
   (binding [test/report report]
     (test/do-report {:type :begin-test-ns, :ns ns})
     (let [time-info (atom nil)]
       (timing time-info
         (if-let [test-hook (ns-resolve ns 'test-ns-hook)]
           (test-hook)
           (test-vars ns vars fail-fast?)))
       (test/do-report {:type :end-test-ns
                        :ns ns
                        :ns-elapsed-time @time-info})
       @current-report))))

(defn test-var-query
  "Call `test-ns` for each var found via var-query."
  ([var-query]
   (test-var-query var-query false))

  ([var-query fail-fast?]
   (report-reset!)
   (let [elapsed-time (atom nil)
         corpus (group-by
                 (comp :ns meta)
                 (query/vars var-query))]
     (timing elapsed-time
       (reduce (fn [_ [ns vars]]
                 (cond-> (test-ns ns vars fail-fast?)
                   (and fail-fast? (current-test-run-failed?))
                   reduced))
               nil
               corpus))
     (assoc @current-report :elapsed-time @elapsed-time))))

(defn test-nss
  "Call `test-ns` for each entry in map `m`, in which keys are namespace
  symbols and values are var symbols to be tested in that namespace (or `nil`
  to test all vars). Symbols are first resolved to their corresponding
  objects."
  ([m]
   (test-nss m false))

  ([m fail-fast?]
   (report-reset!)
   (let [elapsed-time (atom nil)
         corpus (mapv (fn [[ns vars]]
                        [(the-ns ns)
                         (keep (partial ns-resolve ns) vars)])
                      m)]
     (timing elapsed-time
       (reduce (fn [_ [ns vars]]
                 (cond-> (test-ns ns vars fail-fast?)
                   (and fail-fast? (current-test-run-failed?))
                   reduced))
               nil
               corpus))
     (assoc @current-report :elapsed-time @elapsed-time))))

;;; ## Middleware

(def results
  "An atom holding results of test runs, indexed by namespace. This is used to
  reference exception objects from erring tests, and to rerun tests (by
  namespace) that did not pass previously. The var itself will be bound from
  the nREPL session."
  (atom {}))

(defn handle-test-var-query-op
  [{:keys [fail-fast var-query transport session id] :as msg}]
  (let [fail-fast? (= "true" fail-fast)
        {:keys [exec]} (meta session)]
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
                                 (test-var-query fail-fast?)
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
  [{:keys [transport session id fail-fast] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (with-bindings (assoc @session #'ie/*msg* msg)
              (let [nss (reduce (fn [ret [ns tests]]
                                  (let [problems (filter (comp #{:fail :error} :type)
                                                         (mapcat val tests))
                                        vars (distinct (map :var problems))]
                                    (if (seq vars)
                                      (assoc ret ns vars)
                                      ret)))
                                {} @results)
                    report (test-nss nss (= "true" fail-fast))]
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
                  (doseq [cause (stacktrace/analyze e print-fn)]
                    (t/send transport (response-for msg cause)))
                  (t/send transport (response-for msg :status :no-error))))))
          (fn []
            (t/send transport (response-for msg :status :done))))))

(defn handle-test [handler msg & _configuration]
  (case (:op msg)
    ;; (NOTE: deprecated)
    "test"            (handle-test-op msg)
    ;; (NOTE: deprecated)
    "test-all"        (handle-test-all-op msg)

    "test-var-query"  (handle-test-var-query-op msg)
    "test-stacktrace" (handle-stacktrace-op msg)
    "retest"          (handle-retest-op msg)
    (handler msg)))
