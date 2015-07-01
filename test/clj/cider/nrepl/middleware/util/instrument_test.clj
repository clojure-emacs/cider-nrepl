(ns cider.nrepl.middleware.util.instrument-test
  (:require [cider.nrepl.middleware.debug :as d]
            [cider.nrepl.middleware.util.instrument :as t]
            [clojure.repl :as repl]
            [clojure.test :refer :all]
            [clojure.walk :as walk]))

(deftest dont-break?
  (are [x] (#'t/dont-break? (walk/macroexpand-all x))
    '(defn name "" [] (inc 2))
    '(defn- name "" [] (inc 2))
    '(def name "")
    '(fn name [] (inc 2))
    '(fn* name ([] (inc 2))))
  (are [x] (#'t/dont-break? x)
    '(if 1 (recur (inc 2)) 0))
  (are [x] (not (#'t/dont-break? x))
    '(loop [] (if 1 (recur (inc 2)) 0))
    '(inc 1)
    '(inc 2)))

(deftest listy?
  (are [x] (#'t/listy? x)
    '()
    `()
    '(a)
    (second `[b (inc 1)])
    (map inc (range)))

  (are [x] (not (#'t/listy? x))
    nil
    []
    {}
    #{}))

(deftest interesting-symbol?
  (are [x] (not (#'t/interesting-symbol? x))
    'map 'range 'inc 'dec
    :batman :scarecrow)
  (are [x] (#'t/interesting-symbol? x)
    'killer-croc
    'hannah-montana))

(def bp-tracker (atom #{}))
(defmacro bp [value coor]
  (swap! bp-tracker conj [value coor])
  value)

(defn breakpoint-tester [form]
  (with-redefs [d/breakpoint-reader
                (fn [x] (t/with-meta-safe x {:cider-breakfunction #'bp}))]
    (reset! bp-tracker #{})
    (walk/macroexpand-all (#'t/instrument-tagged-code (#'d/debug-reader form)))
    ;; Replace #'bp with 'bp for easier print and comparison.
    (walk/postwalk #(if (= % #'bp) 'bp %) @bp-tracker)))

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
    '#{[final [6]] [x [1 1]] [never [4]] [(= (bp x [1 1]) 1) [1]]}))

(deftest instrument-recur
  (is (= (breakpoint-tester '(loop [x '(1 2)]
                               (if (seq x)
                                 (recur (rest x))
                                 x)))
         '#{[(rest (bp x [2 2 1 1])) [2 2 1]]
            [x [2 2 1 1]]
            [x [2 1 1]]
            [x [2 3]]
            [(seq (bp x [2 1 1])) [2 1]]}))

  (is (= (breakpoint-tester '(fn [x]
                               (if (seq x)
                                 (recur (rest x))
                                 x)))
         '#{[(rest (bp x [2 2 1 1])) [2 2 1]]
            [x [2 2 1 1]]
            [x [2 1 1]]
            [x [2 3]]
            [(seq (bp x [2 1 1])) [2 1]]})))

(deftest instrument-reify
  (is (= (breakpoint-tester '(reify Transport
                               (recv [this] (.recv transport))
                               (send [this response]
                                 (if (contains? response :value)
                                   (inspect-reply msg response)
                                   (.send transport response))
                                 this)))
         '#{[this [3 3]]
            [(contains? (bp response [3 2 1 1]) :value) [3 2 1]]
            [msg [3 2 2 1]]
            [(inspect-reply (bp msg [3 2 2 1]) (bp response [3 2 2 2])) [3 2 2]]
            [(. (bp transport [3 2 3 1]) send (bp response [3 2 3 2])) [3 2 3]]
            [(if (bp (contains? (bp response [3 2 1 1]) :value) [3 2 1])
               (bp (inspect-reply (bp msg [3 2 2 1]) (bp response [3 2 2 2])) [3 2 2])
               (bp (. (bp transport [3 2 3 1]) send (bp response [3 2 3 2])) [3 2 3])) [3 2]]
            [transport [3 2 3 1]]
            [transport [2 2 1]]
            [response [3 2 3 2]]
            [response [3 2 1 1]]
            [response [3 2 2 2]]
            [(. (bp transport [2 2 1]) recv) [2 2]]})))

(deftest instrument-function-call
  (is (empty? (breakpoint-tester '(System/currentTimeMillis))))
  (is (= (breakpoint-tester
          '(defn test-fn []
             (let [start-time (System/currentTimeMillis)]
               (Thread/sleep 1000)
               (- (System/currentTimeMillis) start-time))))
         '#{[(- (bp (. System currentTimeMillis) [3 3 1]) (bp start-time [3 3 2])) [3 3]]
            [start-time [3 3 2]]
            [(. Thread sleep 1000) [3 2]]
            [(. System currentTimeMillis) [3 3 1]]
            [(. System currentTimeMillis) [3 1 1]]
            [(let* [start-time (bp (. System currentTimeMillis) [3 1 1])]
                   (bp (. Thread sleep 1000) [3 2])
                   (bp (- (bp (. System currentTimeMillis) [3 3 1]) (bp start-time [3 3 2])) [3 3]))
             [3]]})))
