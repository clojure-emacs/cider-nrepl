(ns cider.nrepl.middleware.macroexpand-test
  (:require [cider.nrepl.middleware.macroexpand :refer :all]
            [cider.nrepl.test-transport :refer :all]
            [clojure.set :as set]
            [clojure.string]
            [clojure.test :refer :all]))

(def code
  {:expr         "(while (while 1))"
   :expanded     "(loop* [] (when (while 1) (recur)))"
   :expanded-1   "(loop [] (when (while 1) (recur)))"
   :expanded-all "(loop* [] (if (loop* [] (if 1 (do (recur)))) (do (recur))))"})

(deftest test-macroexpand-1-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :expander "macroexpand-1"
                           :code (:expr code)
                           :ns "clojure.core"
                           :display-namespaces "none"})
    (is (= (messages transport)
           [{:expansion (:expanded-1 code) :status #{:done}}]))))

(deftest test-macroexpand-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :expander "macroexpand"
                           :code (:expr code)
                           :ns "clojure.core"
                           :display-namespaces "none"})
    (is (= (messages transport)
           [{:expansion (:expanded code) :status #{:done}}]))))

(deftest test-macroexpand-all-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :expander "macroexpand-all"
                           :code (:expr code)
                           :ns "clojure.core"
                           :display-namespaces "none"})
    (is (= (messages transport)
           [{:expansion (:expanded-all code) :status #{:done}}]))))

;; Tests for the three different cider-macroexpansion-display-namespaces
;; values: nil, t, and 'tidy

(def my-set #{2 3})
(defmacro ^:private tidy-test-macro []
  `(deftest ~'test-foo
     (is (clojure.string/blank? ""))
     (is (= my-set (set/intersection #{1 2 3} #{2 3 4})))))

(deftest test-macroexpand-1-op-display-namespaces-qualified
  ;; Tests that every var is properly qualified
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :expander "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :display-namespaces "qualified"})
    (let [[val] (messages transport)]
      (is (= (:status val) #{:done}))
      (is (= (clojure.string/replace (:expansion val) #"[ \t\n]+" " ")
             ;; format the set literals instead of hard-coding them in the
             ;; string because with different clojure versions, the set #{1 2
             ;; 3} might also be printed as #{1 3 2} or #{3 2 1}.
             (format "(clojure.test/deftest test-foo (clojure.test/is (clojure.string/blank? \"\")) (clojure.test/is (clojure.core/= cider.nrepl.middleware.macroexpand-test/my-set (clojure.set/intersection %s %s))))"
                     #{1 2 3} #{2 3 4}))))))

(deftest test-macroexpand-1-op-display-namespaces-none
  ;; Tests that no var is qualified with its namespace
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :expander "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :display-namespaces "none"})
    (let [[val] (messages transport)]
      (is (= (:status val) #{:done}))
      (is (= (clojure.string/replace (:expansion val) #"[ \t\n]+" " ")
             ;; format the set literals instead of hard-coding them in the
             ;; string because with different clojure versions, the set #{1 2
             ;; 3} might also be printed as #{1 3 2} or #{3 2 1}.
             (format "(deftest test-foo (is (blank? \"\")) (is (= my-set (intersection %s %s))))"
                     #{1 2 3} #{2 3 4}))))))

(deftest test-macroexpand-1-op-display-namespaces-tidy
  ;; Tests that refered vars (deftest, is) and vars of the current ns (my-set)
  ;; are not qualified.  Vars from other namespaces with an alias are
  ;; referenced with the alias (set/intersection).  Every other var is fully
  ;; qualified (clojure.string/blank?).
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :expander "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :display-namespaces "tidy"})
    (let [[val] (messages transport)]
      (is (= (:status val) #{:done}))
      (is (= (clojure.string/replace (:expansion val) #"[ \t\n]+" " ")
             ;; format the set literals instead of hard-coding them in the
             ;; string because with different clojure versions, the set #{1 2
             ;; 3} might also be printed as #{1 3 2} or #{3 2 1}.
             (format "(deftest test-foo (is (clojure.string/blank? \"\")) (is (= my-set (set/intersection %s %s))))"
                     #{1 2 3} #{2 3 4}))))))

(deftest test-macroexpand-1-op-print-meta
  (let [transport (test-transport)
        _ (macroexpansion-reply {:transport transport
                                 :op "macroexpand"
                                 :expander "macroexpand-1"
                                 :code "(defn x [] nil)"
                                 :ns "cider.nrepl.middleware.macroexpand-test"
                                 :display-namespaces "tidy"
                                 :print-meta "true"})
        [val] (messages transport)]
    (is (= (:status val) #{:done}))
    (is (= (:expansion val) "(def ^{:arglists (quote ([]))} x (fn ([] nil)))"))))
