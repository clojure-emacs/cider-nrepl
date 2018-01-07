(ns cider.nrepl.middleware.test
  "Test execution, reporting, and inspection"
  {:author "Jeff Valk"}
  (:require [cider.nrepl.middleware.pprint :as pprint]
            [cider.nrepl.middleware.stacktrace :as st]
            [cider.nrepl.middleware.util.misc :as u]
            [cider.nrepl.middleware.util.namespace :as ns]
            [clojure.pprint :as pp]
            [clojure.test :as test]
            [clojure.tools.nrepl.middleware.interruptible-eval :as ie]
            [clojure.tools.nrepl.middleware.pr-values :refer [pr-values]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as t]))

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

(defn test-result
  "Transform the result of a test assertion. Append ns, var, assertion index,
  and 'testing' context. Retain any exception. Pretty-print expected/actual."
  [ns v m]
  (let [c (when (seq test/*testing-contexts*) (test/testing-contexts-str))
        i (count (get-in (@current-report :results) [ns (:name (meta v))]))
        t (:type m)
        gen-input (:gen-input @current-report)]
    ;; Errors outside assertions (faults) do not return an :expected value.
    ;; Type :fail returns :actual value. Type :error returns :error and :line.
    (merge (dissoc m :expected :actual)
           {:ns ns, :var (:name (meta v)), :index i, :context c}
           (when (and (#{:fail :error} t) (not (:fault m)))
             {:expected (with-out-str (pp/pprint (:expected m)))})
           (when (and (#{:fail} t) gen-input)
             {:gen-input (with-out-str (pp/pprint gen-input))})
           (when (#{:fail} t)
             {:actual (with-out-str (pp/pprint (:actual m)))})
           (when (#{:error} t)
             (let [e (:actual m)
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

(defn report
  "Handle reporting for test events. This takes a test event map as an argument
  and updates the `current-report` atom to reflect test results and summary
  statistics."
  [m]
  (let [ns (ns-name (get m :ns (:testing-ns @current-report)))
        v  (last test/*testing-vars*)
        update! (partial swap! current-report update-in)]
    (condp get (:type m)
      #{:begin-test-ns}     (do (update! [:testing-ns] (constantly ns))
                                (update! [:summary :ns] inc))
      #{:begin-test-var}    (do (update! [:summary :var] inc))
      #{:pass :fail :error} (do (update! [:summary :test] inc)
                                (update! [:summary (:type m)] inc)
                                (update! [:results ns (:name (meta v))]
                                         (fnil conj []) (test-result ns v m))
                                (update! [:gen-input] (constantly nil))) ; reset

      #{:com.gfredericks.test.chuck.clojure-test/shrunk}
      (do (update! [:gen-input] (constantly (-> m :shrunk :smallest))))

      #{:clojure.test.check.clojure-test/shrinking
        :clojure.test.check.clojure-test/shrunk}
      (do (update! [:gen-input]
                   (constantly (:clojure.test.check.clojure-test/params m))))

      nil)))

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
  "Call `test-var` on each var, with the fixtures defined for namespace
  object `ns`."
  [ns vars]
  (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta ns)))
        each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta ns)))]
    (try (once-fixture-fn
          (fn []
            (doseq [v vars]
              (when (:test (meta v))
                (each-fixture-fn (fn [] (test-var v)))))))
         (catch Throwable e
           (report-fixture-error ns e)))))

(defn test-ns
  "If the namespace object defines a function named `test-ns-hook`, call that.
  Otherwise, test the specified vars. If no vars are specified, test all vars
  in the namespace. On completion, return a map of test results."
  [ns vars]
  (binding [test/report report]
    (test/do-report {:type :begin-test-ns, :ns ns})
    (if-let [test-hook (ns-resolve ns 'test-ns-hook)]
      (test-hook)
      (test-vars ns (or (seq vars)
                        (vals (ns-interns ns)))))
    (test/do-report {:type :end-test-ns, :ns ns})
    @current-report))

(defn test-nss
  "Call `test-ns` for each entry in map `m`, in which keys are namespace symbols
  and values are var symbols to be tested in that namespace (or `nil` to test
  all vars). Symbols are first resolved to their corresponding objects."
  [m]
  (report-reset!)
  (doseq [[ns vars] m]
    (->> (map (partial ns-resolve ns) vars)
         (filter identity)
         (test-ns (the-ns ns))))
  @current-report)


;;; ## Metadata Utils

(defn has-tests?
  "Return a truthy value if the namespace has any `:test` metadata."
  [ns]
  (when-let [ns-obj
             (if (instance? clojure.lang.Namespace ns)
               ns
               (find-ns ns))]
    (seq (filter (comp :test meta val)
                 (ns-interns ns-obj)))))

;;; ## Middleware

(def results
  "An atom holding results of test runs, indexed by namespace. This is used to
  reference exception objects from erring tests, and to rerun tests (by
  namespace) that did not pass previously. The var itself will be bound from
  the nREPL session."
  (atom {}))

(defmacro with-interruptible-eval
  "Run body mimicking interruptible-eval."
  [msg & body]
  `(let [session# (:session ~msg)]
     ;; Before tools.nrepl-0.2.10, `queue-eval` was private.
     (@#'ie/queue-eval session# (:executor ~msg)
      (fn []
        (alter-meta! session# assoc
                     :thread (Thread/currentThread)
                     :eval-msg ~msg)
        (binding [ie/*msg* ~msg]
          (with-bindings @session#
            ~@body)
          (alter-meta! session# dissoc :thread :eval-msg))))))

(defn handle-test-op
  [{:keys [ns tests session transport] :as msg}]
  (with-interruptible-eval msg
    (if-let [ns (ns/ensure-namespace ns)]
      (let [nss {ns (map u/as-sym tests)}
            report (test-nss nss)]
        (reset! results (:results report))
        (t/send transport (response-for msg (u/transform-value report))))
      (t/send transport (response-for msg :status :namespace-not-found)))
    (t/send transport (response-for msg :status :done))))

(defn handle-test-all-op
  [{:keys [load? session transport] :as msg}]
  (with-interruptible-eval msg
    (let [nss (zipmap (->> (if load?
                             (ns/load-project-namespaces)
                             (ns/loaded-project-namespaces))
                           (filter has-tests?))
                      (repeat nil))
          report (test-nss nss)]
      (reset! results (:results report))
      (t/send transport (response-for msg (u/transform-value report))))
    (t/send transport (response-for msg :status :done))))

(defn handle-retest-op
  [{:keys [session transport] :as msg}]
  (with-interruptible-eval msg
    (let [nss (reduce (fn [ret [ns tests]]
                        (let [problems (filter (comp #{:fail :error} :type)
                                               (mapcat val tests))
                              vars (distinct (map :var problems))]
                          (if (seq vars) (assoc ret ns vars) ret)))
                      {} @results)
          report (test-nss nss)]
      (reset! results (:results report))
      (t/send transport (response-for msg (u/transform-value report))))
    (t/send transport (response-for msg :status :done))))

(defn handle-stacktrace-op
  [{:keys [ns var index session transport pprint-fn] :as msg}]
  (with-interruptible-eval msg
    (let [[ns var] (map u/as-sym [ns var])]
      (if-let [e (get-in @results [ns var index :error])]
        (doseq [cause (st/analyze-causes e pprint-fn)]
          (t/send transport (response-for msg cause)))
        (t/send transport (response-for msg :status :no-error)))
      (t/send transport (response-for msg :status :done)))))

;; Before tools.nrepl-0.2.10, `default-executor` was private and
;; before 0.2.9 it didn't even exist.
(def default-executor (delay (if-let [def (resolve 'ie/default-executor)]
                               @@def
                               (@#'ie/configure-executor))))

(defn handle-test [handler msg & configuration]
  (let [executor (:executor configuration @default-executor)]
    (case (:op msg)
      "test"            (handle-test-op (assoc msg :executor executor))
      "test-all"        (handle-test-all-op (assoc msg :executor executor))
      "test-stacktrace" (handle-stacktrace-op (assoc msg :executor executor))
      "retest"          (handle-retest-op (assoc msg :executor executor))
      (handler msg))))
