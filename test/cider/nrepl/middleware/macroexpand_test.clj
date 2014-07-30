(ns cider.nrepl.middleware.macroexpand-test
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.macroexpand)
  (:require [clojure.set :as set]
            [clojure.string]))

(def code
  {:expr         "(while (while 1))"
   :expanded     "(loop* [] (when (while 1) (recur)))"
   :expanded-1   "(loop [] (when (while 1) (recur)))"
   :expanded-all "(loop* [] (if (loop* [] (if 1 (do (recur)))) (do (recur))))"})

(deftest test-macroexpand-1-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-1"
                           :code (:expr code)
                           :ns "clojure.core"
                           :suppress-namespaces true})
    (is (= (messages transport)
           [{:value (:expanded-1 code)} {:status #{:done}}]))))

(deftest test-macroexpand-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :code (:expr code)
                           :ns "clojure.core"
                           :suppress-namespaces true})
    (is (= (messages transport)
           [{:value (:expanded code)} {:status #{:done}}]))))

(deftest test-macroexpand-all-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-all"
                           :code (:expr code)
                           :ns "clojure.core"
                           :suppress-namespaces true})
    (is (= (messages transport)
           [{:value (:expanded-all code)} {:status #{:done}}]))))

;; Tests for the three different cider-macroexpansion-suppress-namespaces
;; values: nil, t, and 'tidy

(def my-set #{2 3})
(defmacro ^:private tidy-test-macro []
  `(deftest ~'test-foo
     (is (clojure.string/blank? ""))
     (is (= my-set (set/intersection #{1 2 3} #{2 3 4})))))

(deftest test-macroexpand-1-op-suppress-namespaces-nil
  ;; Tests that every var is properly qualified
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :suppress-namespaces nil})
    (let [[val stat] (messages transport)]
      (is (= (:status stat) #{:done}))
      (is (= (clojure.string/replace (:value val) #"[ \t\n]+" " ")
             "(clojure.test/deftest test-foo (clojure.test/is (clojure.string/blank? \"\")) (clojure.test/is (clojure.core/= cider.nrepl.middleware.macroexpand-test/my-set (clojure.set/intersection #{1 2 3} #{2 3 4}))))")))))

(deftest test-macroexpand-1-op-suppress-namespaces-true
  ;; Tests that no var is qualified with its namespace
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :suppress-namespaces true})
    (let [[val stat] (messages transport)]
      (is (= (:status stat) #{:done}))
      (is (= (clojure.string/replace (:value val) #"[ \t\n]+" " ")
             "(deftest test-foo (is (blank? \"\")) (is (= my-set (intersection #{1 2 3} #{2 3 4}))))")))))

(deftest test-macroexpand-1-op-suppress-namespaces-tidy
  ;; Tests that refered vars (deftest, is) and vars of the current ns (my-set)
  ;; are not qualified.  Vars from other namespaces with an alias are
  ;; referenced with the alias (set/intersection).  Every other var is fully
  ;; qualified (clojure.string/blank?).
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :suppress-namespaces "tidy"})
    (let [[val stat] (messages transport)]
      (is (= (:status stat) #{:done}))
      (is (= (clojure.string/replace (:value val) #"[ \t\n]+" " ")
             "(deftest test-foo (is (clojure.string/blank? \"\")) (is (= my-set (set/intersection #{1 2 3} #{2 3 4}))))")))))
