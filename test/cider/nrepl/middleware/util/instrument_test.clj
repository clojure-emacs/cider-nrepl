(ns cider.nrepl.middleware.util.instrument-test
  (:require [clojure.test :refer :all]
            [clojure.repl :as repl]
            [cider.nrepl.middleware.util.instrument :as t]))

(deftest listy?
  (are [x] (#'t/listy? x)
    '()
    `()
    '(a)
    (second `[b (inc 1)]))

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

(deftest macro-arglists
  (are [s a] (= (#'t/macro-arglists s) a)
    'if   '([test then else?])
    'when '([test & body])
    'let  '([bindings & body])
    'defn '([name doc-string? attr-map? [params*] prepost-map? body]
            [name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])
    'def  '([symbol doc-string? init?])))

(deftest reorder-+
  (are [a b] (= (#'t/reorder-+ a) b)
    '[+ 1 2 3 4] '[+ 1 2 3 4]
    '[1 + 2 3 4] '[+ 1 2 3 4]
    '[1 2 + 3 4] '[1 + 2 3 4]
    '[1 2 3 + 4] '[1 2 + 3 4]
    '[1 2 3 4 +] '[1 2 3 + 4]
    '[1 2 3 4] '[1 2 3 4]))

(deftest always-1
  (are [a] (= (#'t/always-1 a) 1)
    '[+ 1 2 3 4] '(+ 1 2 3 4)
    '([& expr]) '([test & body])
    'pikachu
    "Charizard"
    :Blastoise))

;;; Dummy ex
(def dex {:coor [13] :breakfunction 'b})

(deftest instrument-nothing
  (are [a b] (= (#'t/instrument-nothing '{:coor a} b) b)
    '[+ 1 2 3 4] '(+ 1 2 3 4)
    '([& expr]) '([test & body])
    'pikachu "Charizard"
    :Blastoise :Magikarp))

(deftest instrument-map
  (is (= (#'t/instrument-map dex '{:a 1, (name :b) (inc 2)})
         '{:a 1,
           (b (name :b) {:breakfunction b, :coor [13 2]})
           (b (inc 2) {:breakfunction b, :coor [13 3]})})))

(deftest specifier-match-bindings
  (are [f] (= 1 (#'t/specifier-match-bindings f))
    '([] sherlock)
    '([watson (moriarty) watson (moriarty) watson (moriarty)])
    '([watson (moriarty) watson (moriarty) watson (moriarty)] sherlock)
    '([]))
  (are [f] (not (#'t/specifier-match-bindings f))
    '(() sherlock)
    '([watson (moriarty) watson (moriarty) watson])
    '([(moriarty) watson (moriarty)] sherlock)
    '(10)))

(deftest specifier-destructure
  (are [s o] (let [[[matcher handler] & r]
                   (#'t/specifier-destructure s)]
               (and (= r o)
                    (function? matcher)
                    (function? handler)))
    'binding '(false false)
    'bindings '(false false)
    'expr '(false false)
    'expr* '(false true)
    'expr? '(true false)
    'exprs? '(true false)
    'forms* '(false true)
    'tarzan '(false false))
  (is (= (#'t/specifier-destructure "bindings")
         (#'t/specifier-destructure "binding"))))
