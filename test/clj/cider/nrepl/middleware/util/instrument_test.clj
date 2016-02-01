(ns cider.nrepl.middleware.util.instrument-test
  (:require [cider.nrepl.middleware.util.instrument :as t]
            [cider.nrepl.middleware.util.meta :as m]
            [clojure.test :refer :all]
            [clojure.walk :as walk]))

(deftest dont-break?
  (are [x] (#'t/dont-break? x)
    '(if 1 (recur (inc 2)) 0))
  (are [x] (not (#'t/dont-break? (walk/macroexpand-all x)))
    '(loop [] (if 1 (recur (inc 2)) 0))
    '(inc 1)
    '(inc 2)))

(def bp-tracker (atom #{}))
(defmacro bp [value coor & _]
  (swap! bp-tracker conj [value coor])
  value)

(defn breakpoint-tester [form]
  (reset! bp-tracker #{})
  (-> (t/tag-form-recursively form #'bp)
      t/instrument-tagged-code
      ;; (#(do (prn %) %))
      ;; A final macroexpand-all to cause the `bp` macro above to
      ;; execute. In regular usage, this would be a complete
      ;; expand+eval done by the Clojure compiler.
      m/macroexpand-all)
  ;; Replace #'bp with 'bp for easier print and comparison.
  (walk/postwalk #(if (= % #'bp) 'bp %) @bp-tracker))

(deftest instrument-clauses
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
    '#{[final [6]] [x [1 1]] [never [4]] [(= (bp x [1 1] x) 1) [1]]}))

(deftest instrument-recur
  (is (= (breakpoint-tester '(loop [x '(1 2)]
                               (if (seq x)
                                 (recur (rest x))
                                 x)))
         '#{[(loop* [x '(1 2)] (if (bp (seq (bp x [2 1 1] x)) [2 1] (seq x)) (recur (bp (rest (bp x [2 2 1 1] x)) [2 2 1] (rest x))) (bp x [2 3] x))) []]
            [(rest (bp x [2 2 1 1] x)) [2 2 1]]
            [x [2 2 1 1]]
            [x [2 1 1]]
            [x [2 3]]
            [(seq (bp x [2 1 1] x)) [2 1]]}))

  (is (= (breakpoint-tester '(fn [x]
                               (if (seq x)
                                 (recur (rest x))
                                 x)))
         '#{[(rest (bp x [2 2 1 1] x)) [2 2 1]]
            [x [2 2 1 1]]
            [x [2 1 1]]
            [x [2 3]]
            [(seq (bp x [2 1 1] x)) [2 1]]})))

(deftest instrument-reify
  (is (= (breakpoint-tester '(reify Transport
                               (recv [this] (.recv transport))
                               (send [this response]
                                 (if (contains? response :value)
                                   (inspect-reply msg response)
                                   (.send transport response))
                                 this)))
         '#{[(. (bp transport [3 2 3 1] transport) send (bp response [3 2 3 2] response)) [3 2 3]]
            [response [3 2 1 1]]
            [response [3 2 2 2]]
            [response [3 2 3 2]]
            [(reify* [Transport] (recv [this] (bp (. (bp transport [2 2 1] transport) recv) [2 2] (.recv transport))) (send [this response] (bp (if (bp (contains? (bp response [3 2 1 1] response) :value) [3 2 1] (contains? response :value)) (bp (inspect-reply (bp msg [3 2 2 1] msg) (bp response [3 2 2 2] response)) [3 2 2] (inspect-reply msg response)) (bp (. (bp transport [3 2 3 1] transport) send (bp response [3 2 3 2] response)) [3 2 3] (.send transport response))) [3 2] (if (contains? response :value) (inspect-reply msg response) (.send transport response))) (bp this [3 3] this))) []]
            [transport [2 2 1]]
            [(inspect-reply (bp msg [3 2 2 1] msg) (bp response [3 2 2 2] response)) [3 2 2]]
            [transport [3 2 3 1]]
            [this [3 3]]
            [(if (bp (contains? (bp response [3 2 1 1] response) :value) [3 2 1] (contains? response :value)) (bp (inspect-reply (bp msg [3 2 2 1] msg) (bp response [3 2 2 2] response)) [3 2 2] (inspect-reply msg response)) (bp (. (bp transport [3 2 3 1] transport) send (bp response [3 2 3 2] response)) [3 2 3] (.send transport response))) [3 2]]
            [msg [3 2 2 1]]
            [(contains? (bp response [3 2 1 1] response) :value) [3 2 1]]
            [(. (bp transport [2 2 1] transport) recv) [2 2]]})))

(deftest instrument-function-call
  (is (= (breakpoint-tester
          '(defn test-fn []
             (let [start-time (System/currentTimeMillis)]
               (Thread/sleep 1000)
               (- (System/currentTimeMillis) start-time))))
         '#{[(def test-fn (fn* ([] (bp (let* [start-time (bp (. System currentTimeMillis) [3 1 1] (System/currentTimeMillis))] (bp (. Thread sleep 1000) [3 2] (Thread/sleep 1000)) (bp (- (bp (. System currentTimeMillis) [3 3 1] (System/currentTimeMillis)) (bp start-time [3 3 2] start-time)) [3 3] (- (System/currentTimeMillis) start-time))) [3] (let [start-time (System/currentTimeMillis)] (Thread/sleep 1000) (- (System/currentTimeMillis) start-time)))))) []]
            [(let* [start-time (bp (. System currentTimeMillis) [3 1 1] (System/currentTimeMillis))] (bp (. Thread sleep 1000) [3 2] (Thread/sleep 1000)) (bp (- (bp (. System currentTimeMillis) [3 3 1] (System/currentTimeMillis)) (bp start-time [3 3 2] start-time)) [3 3] (- (System/currentTimeMillis) start-time))) [3]]
            [(- (bp (. System currentTimeMillis) [3 3 1] (System/currentTimeMillis)) (bp start-time [3 3 2] start-time)) [3 3]]
            [(. System currentTimeMillis) [3 1 1]]
            [(. Thread sleep 1000) [3 2]]
            [start-time [3 3 2]]
            [(. System currentTimeMillis) [3 3 1]]})))

(deftest instrument-try
  ;; No breakpoints around `catch`, `finally`, `Exception`, or `e`.
  (is (= (breakpoint-tester '(try
                               x
                               (catch Exception e z)
                               (finally y)))
         '#{[y [3 1]]
            [x [1]]
            [z [2 3]]
            [(try (bp x [1] x) (catch Exception e (bp z [2 3] z)) (finally (bp y [3 1] y))) []]})))

(deftest instrument-def
  (is (= (breakpoint-tester '(def foo (bar)))
         '#{[(def foo (bp (bar) [2] (bar))) []]
            [(bar) [2]]}))
  (is (= (breakpoint-tester '(def foo "foo doc" (bar)))
         '#{[(def foo "foo doc" (bp (bar) [3] (bar))) []]
            [(bar) [3]]})))

(deftest instrument-set!
  (is (= (breakpoint-tester '(set! foo (bar)))
         '#{[(set! foo (bp (bar) [2] (bar))) []]
            [(bar) [2]]}))
  (is (= (breakpoint-tester '(set! (. inst field) (bar)))
         '#{[(set! (. inst field) (bp (bar) [2] (bar))) []]
            [(bar) [2]]}))
  (is (= (breakpoint-tester '(set! (.field inst) (bar)))
         '#{[(set! (. inst field) (bp (bar) [2] (bar))) []]
            [(bar) [2]]})))

(deftest instrument-deftest
  (binding [*ns* (the-ns 'cider.nrepl.middleware.util.instrument-test)]
    (is (= (breakpoint-tester '(deftest foo (bar)))
           '#{[(def foo (fn* ([] (clojure.test/test-var (var foo))))) []]
              [(bar) [2]]}))))
