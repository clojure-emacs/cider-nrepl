(ns cider.nrepl.middleware.util.instrument-test
  (:require [cider.nrepl.middleware.util.instrument :as t]
            [cider.nrepl.middleware.util.meta :as m]
            [clojure.test :refer :all]
            [clojure.walk :as walk]))

(deftest dont-break?-test
  (are [x] (#'t/dont-break? x)
    '(if 1 (recur (inc 2)) 0))
  (are [x] (not (#'t/dont-break? (walk/macroexpand-all x)))
    '(loop [] (if 1 (recur (inc 2)) 0))
    '(inc 1)
    '(inc 2)))

(def bp-tracker (atom #{}))
(defmacro bp [value coor & _]
  (swap! bp-tracker conj [value (:coor coor)])
  value)

(defn breakpoint-tester [form]
  (reset! bp-tracker #{})
  (-> (m/strip-meta form)
      (t/tag-form-recursively #'bp)
      t/instrument-tagged-code
      ;; (#(do (prn %) %))
      ;; A final macroexpand-all to cause the `bp` macro above to
      ;; execute. In regular usage, this would be a complete
      ;; expand+eval done by the Clojure compiler.
      m/macroexpand-all)
  ;; Replace #'bp with 'bp for easier print and comparison.
  (walk/postwalk #(if (= % #'bp) 'bp %) @bp-tracker))

(deftest instrument-defrecord-and-new-test
  (are [exp res] (clojure.set/subset? res (breakpoint-tester exp))
    '(defrecord TestRec [arg arg2]
       java.lang.Iterable
       (iterator [this] (inc 1)))
    '#{[(inc 1) [4 2]]}

    '(new java.lang.Integer 1)
    '#{[(new java.lang.Integer 1) []]}))

(deftest instrument-clauses-test
  (are [exp res] (clojure.set/subset? res (breakpoint-tester exp))
    '(cond-> value
       v2 form
       v3 (boogie oogie form))
    '#{[oogie [5 1]] [form [5 2]] [value [1]] [v2 [2]] [v3 [4]]}

    '(case value
       some-const some-expr
       the-default)
    '#{[the-default [4]] [some-expr [3]] [value [1]]}

    '(condp pred value
       v4 :>> v5)
    '#{[pred [1]] [v4 [3]] [value [2]]}

    '(condp pred value
       v4 v5)
    '#{[pred [1]] [v5 [4]] [v4 [3]] [value [2]]}

    '(condp pred value
       v2 v3
       default)
    '#{[pred [1]] [default [5]] [value [2]] [v3 [4]] [v2 [3]]}

    '(cond
       (= x 1) true
       false   never
       :else   final)
    '#{[final [6]] [x [1 1]] [never [4]] [(= (bp x {:coor [1 1]} x) 1) [1]]}))

(deftest instrument-recur-test
  (is (= (breakpoint-tester '(loop [x '(1 2)]
                               (if (seq x)
                                 (recur (rest x))
                                 x)))
         '#{[(loop* [x '(1 2)] (if (bp (seq (bp x {:coor [2 1 1]} x)) {:coor [2 1]} (seq x)) (recur (bp (rest (bp x {:coor [2 2 1 1]} x)) {:coor [2 2 1]} (rest x))) (bp x {:coor [2 3]} x))) []]
            [(rest (bp x {:coor [2 2 1 1]} x)) [2 2 1]]
            [x [2 2 1 1]]
            [x [2 1 1]]
            [x [2 3]]
            [(seq (bp x {:coor [2 1 1]} x)) [2 1]]}))

  (is (= (breakpoint-tester '(fn [x]
                               (if (seq x)
                                 (recur (rest x))
                                 x)))
         '#{[(rest (bp x {:coor [2 2 1 1]} x)) [2 2 1]]
            [x [2 2 1 1]]
            [x [2 1 1]]
            [x [2 3]]
            [(seq (bp x {:coor [2 1 1]} x)) [2 1]]})))

;; Factor this into a separate variable, otherwise Eastwood fails with
;; "method code too large".
(def reify-result
  '#{[(. transport send (bp response {:coor [3 2 3 2]} response)) [3 2 3]]
     [response [3 2 1 1]]
     [response [3 2 2 2]]
     [response [3 2 3 2]]
     [(reify* [Transport] (recv [this] (bp (. transport recv) {:coor [2 2]} (.recv transport))) (send [this response] (bp (if (bp (contains? (bp response {:coor [3 2 1 1]} response) :value) {:coor [3 2 1]} (contains? response :value)) (bp (inspect-reply (bp msg {:coor [3 2 2 1]} msg) (bp response {:coor [3 2 2 2]} response)) {:coor [3 2 2]} (inspect-reply msg response)) (bp (. transport send (bp response {:coor [3 2 3 2]} response)) {:coor [3 2 3]} (.send transport response))) {:coor [3 2]} (if (contains? response :value) (inspect-reply msg response) (.send transport response))) (bp this {:coor [3 3]} this))) []]
     [(inspect-reply (bp msg {:coor [3 2 2 1]} msg) (bp response {:coor [3 2 2 2]} response)) [3 2 2]]
     [this [3 3]]
     [(if (bp (contains? (bp response {:coor [3 2 1 1]} response) :value) {:coor [3 2 1]} (contains? response :value)) (bp (inspect-reply (bp msg {:coor [3 2 2 1]} msg) (bp response {:coor [3 2 2 2]} response)) {:coor [3 2 2]} (inspect-reply msg response)) (bp (. transport send (bp response {:coor [3 2 3 2]} response)) {:coor [3 2 3]} (.send transport response))) [3 2]]
     [msg [3 2 2 1]]
     [(contains? (bp response {:coor [3 2 1 1]} response) :value) [3 2 1]]
     [(. transport recv) [2 2]]})

(deftest instrument-reify-test
  (is (= (breakpoint-tester '(reify Transport
                               (recv [this] (.recv transport))
                               (send [this response]
                                 (if (contains? response :value)
                                   (inspect-reply msg response)
                                   (.send transport response))
                                 this)))
         reify-result)))

(deftest instrument-function-call-test
  (is (= (breakpoint-tester
          '(defn test-fn []
             (let [start-time (System/currentTimeMillis)]
               (Thread/sleep 1000)
               (- (System/currentTimeMillis) start-time))))
         '#{[(def test-fn (fn* ([] (bp (let* [start-time (bp (. System currentTimeMillis) {:coor [3 1 1]} (System/currentTimeMillis))] (bp (. Thread sleep 1000) {:coor [3 2]} (Thread/sleep 1000)) (bp (- (bp (. System currentTimeMillis) {:coor [3 3 1]} (System/currentTimeMillis)) (bp start-time {:coor [3 3 2]} start-time)) {:coor [3 3]} (- (System/currentTimeMillis) start-time))) {:coor [3]} (let [start-time (System/currentTimeMillis)] (Thread/sleep 1000) (- (System/currentTimeMillis) start-time)))))) []]
            [(let* [start-time (bp (. System currentTimeMillis) {:coor [3 1 1]} (System/currentTimeMillis))] (bp (. Thread sleep 1000) {:coor [3 2]} (Thread/sleep 1000)) (bp (- (bp (. System currentTimeMillis) {:coor [3 3 1]} (System/currentTimeMillis)) (bp start-time {:coor [3 3 2]} start-time)) {:coor [3 3]} (- (System/currentTimeMillis) start-time))) [3]]
            [(- (bp (. System currentTimeMillis) {:coor [3 3 1]} (System/currentTimeMillis)) (bp start-time {:coor [3 3 2]} start-time)) [3 3]]
            [(. System currentTimeMillis) [3 1 1]]
            [(. Thread sleep 1000) [3 2]]
            [start-time [3 3 2]]
            [(. System currentTimeMillis) [3 3 1]]})))

(deftest instrument-try-test
  ;; No breakpoints around `catch`, `finally`, `Exception`, or `e`.
  (is (= (breakpoint-tester '(try
                               x
                               (catch Exception e z)
                               (finally y)))
         '#{[y [3 1]]
            [x [1]]
            [z [2 3]]
            [(try (bp x {:coor [1]} x) (catch Exception e (bp z {:coor [2 3]} z)) (finally (bp y {:coor [3 1]} y))) []]})))

(deftest instrument-def
  (is (= (breakpoint-tester '(def foo (bar)))
         '#{[(def foo (bp (bar) {:coor [2]} (bar))) []]
            [(bar) [2]]}))
  (is (= (breakpoint-tester '(def foo "foo doc" (bar)))
         '#{[(def foo "foo doc" (bp (bar) {:coor [3]} (bar))) []]
            [(bar) [3]]})))

(deftest instrument-set!-test
  (is (= (breakpoint-tester '(set! foo (bar)))
         '#{[(set! foo (bp (bar) {:coor [2]} (bar))) []]
            [(bar) [2]]}))
  (is (= (breakpoint-tester '(set! (. inst field) (bar)))
         '#{[(set! (. inst field) (bp (bar) {:coor [2]} (bar))) []]
            [(bar) [2]]}))
  (is (= (breakpoint-tester '(set! (.field inst) (bar)))
         '#{[(set! (. inst field) (bp (bar) {:coor [2]} (bar))) []]
            [(bar) [2]]})))

(deftest instrument-deftest
  (binding [*ns* (the-ns 'cider.nrepl.middleware.util.instrument-test)]
    (is (= (breakpoint-tester '(deftest foo (bar)))
           '#{[(def foo (fn* ([] (clojure.test/test-var (var foo))))) []]
              [(bar) [2]]}))))

(defmacro ns-embedding-macro []
  ;; clojure.tools.logging does this:
  `(identity ~*ns*))

(deftest namespace-embedded-in-code-test
  ;; Instrumentation used to fail if:
  ;;  - a macro embedded a namespace in a form (eg: ~*ns*), and
  ;;  - the namespace had metadata (eg: a docstring), and
  ;;  - you tried to instrument a function that contained a call to the macro
  ;; This is because Namespace objects implement IMeta, but not IObj.
  (let [an-ns (create-ns (with-meta 'foo.bar {:doc "ns docstring"}))]
    (binding [*ns* an-ns]
      (breakpoint-tester '(let [x (cider.nrepl.middleware.util.instrument-test/ns-embedding-macro)] x))
      ;; If the above did not throw, then we pass
      (is true))))
