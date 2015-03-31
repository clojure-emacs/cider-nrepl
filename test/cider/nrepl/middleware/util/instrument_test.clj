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
    '[+ 1 2 3 4]
    '(+ 1 2 3 4)
    '([& expr])
    '([test & body])
    'pikachu
    "Charizard"
    :Blastoise))

;;; Dummy ex
(def dex {:coor [13] :breakfunction 'b})
(defn- id [v] (assoc dex :coor v))
(defmacro bt [form vec]
  `'(~'b ~(eval form) ~(id vec)))

(deftest instrument-nothing
  (are [a b] (= (#'t/instrument-nothing dex b) b)
    '[+ 1 2 3 4] '(+ 1 2 3 4)
    '([& expr])  '([test & body])
    'pikachu     "Charizard"
    :Blastoise   :Magikarp))

(deftest instrument-map
  (is (= (#'t/instrument-map dex '{:a 1, (name :b) (inc 2)})
         {:a 1,
          (bt '(name :b) [13 2])
          (bt '(inc 2) [13 3])})))

(deftest instrument-basics
  (are [f o] (= (f dex '(a b c)) o)
    #'t/instrument-all-args          (list (bt 'a [13]) (bt 'b [14]) (bt 'c [15]))
    #'t/instrument-next-arg          (list (bt 'a [13]) 'b 'c)
    #'t/instrument-nothing           (list 'a 'b 'c)
    #'t/instrument-all-but-first-arg (list 'a (bt 'b [14]) (bt 'c [15]))
    #'t/instrument-second-arg        (list 'a (bt 'b [14]) 'c)
    #'t/instrument-two-args          (list (bt 'a [13]) (bt 'b [14]) 'c)))


(deftest instrument-recur
  (is (= (#'t/instrument dex '(loop [x '(1 2)]
                                (if (seq x)
                                  (recur (rest x))
                                  x)))
         `(~'b (~'loop [~'x ~(bt ''(1 2) [13 1 1])]
                       (~'if (~'b (~'seq ~(bt 'x [13 2 1 1]))
                                  ~(id [13 2 1]))
                             (~'recur (~'b (~'rest ~(bt 'x [13 2 2 1 1]))
                                           ~(id [13 2 2 1])))
                             ~(bt 'x [13 2 3])))
               ~(id [13]))))

  (is (= (#'t/instrument dex '(fn [x]
                                (if (seq x)
                                  (recur (rest x))
                                  x)))
         `(~'fn [~'x]
                (~'if (~'b (~'seq ~(bt 'x [13 2 1 1]))
                           ~(id [13 2 1]))
                      (~'recur (~'b (~'rest ~(bt 'x [13 2 2 1 1]))
                                    ~(id [13 2 2 1])))
                      ~(bt 'x [13 2 3]))))))

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
