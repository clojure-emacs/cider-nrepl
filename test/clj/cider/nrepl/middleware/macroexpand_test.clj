(ns cider.nrepl.middleware.macroexpand-test
  (:refer-clojure :exclude [zero? zipmap])
  (:require
   [cider.nrepl]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.set :as set]
   [clojure.string]
   [clojure.test :refer :all]
   [nrepl.middleware.interruptible-eval]
   [nrepl.middleware.print]
   [nrepl.middleware.session]))

(use-fixtures :once session/session-fixture)

(def code
  {:expr         "(while (while 1))"
   :expanded     "(loop* [] (when (while 1) (recur)))"
   :expanded-1   "(loop [] (when (while 1) (recur)))"
   :expanded-all "(loop* [] (if (loop* [] (if 1 (do (recur)))) (do (recur))))"})

(deftest expander-option-test
  (testing "macroexpand-1 expander works"
    (is+ {:status #{"done"}
          :expansion (:expanded-1 code)}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand-1"
                           :code (:expr code)
                           :display-namespaces "none"})))

  (testing "error handling works"
    (is+ {:status #{"macroexpand-error" "done"}
          :err string?
          :ex string?}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand-1"
                           ;; A faulty sexpr:
                           :code "(let 1)"
                           :display-namespaces "none"})))

  (testing "macroexpand expander works"
    (is+ {:status #{"done"}
          :expansion (:expanded code)}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand"
                           :code (:expr code)
                           :display-namespaces "none"})))

  (testing "macroexpand-all expander works"
    (is+ {:status #{"done"}
          :expansion (:expanded-all code)}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand-all"
                           :code (:expr code)
                           :display-namespaces "none"})))

  (testing "macroexpand-step expander works"
    (letfn [(mstep [code]
              (:expansion (session/message {:op "cider/macroexpand"
                                            :expander "macroexpand-step"
                                            :code code
                                            :display-namespaces "none"})))]
      (let [expansions (take 7 (iterate mstep (:expr code)))]
        (is (= (:expanded-1 code) (nth expansions 1)))
        (is (= (:expanded code) (nth expansions 2)))
        (is (= (:expanded-all code) (nth expansions 6))))))

  (testing "macroexpand is the default expander"
    (is+ {:status #{"done"}
          :expansion (:expanded code)}
         (session/message {:op "cider/macroexpand"
                           :code (:expr code)
                           :display-namespaces "none"})))

  (testing "invalid expander"
    (is+ {:status #{"done" "macroexpand-error"}
          :err some?
          :ex some?
          :pp-stacktrace some?}
         (session/message {:op "cider/macroexpand"
                           :expander "foo"
                           :code "(defn x [] nil)"}))))

(defmacro ^:private lazy-test-macro []
  `(list {:a ~@(lazy-seq [(ns-name *ns*)])}))

(deftest lazy-expand-test
  (testing "lazy macroexpansion expands in the correct namespace"
    (is+ {:status #{"done"}
          :expansion "(list {:a cider.nrepl.middleware.macroexpand-test})"}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :code "(lazy-test-macro)"
                           :display-namespaces "none"}))))

;; Tests for the three possible values of the display-namespaces option:
;; "qualified", "none" and "tidy"

(def zipmap nil)
(def my-set #{2 3})
(defmacro ^:private tidy-test-macro []
  `(deftest ~'test-foo
     ;; excluded var, namespace-qualified
     (is (clojure.core/zero? 0))
     ;; excluded var, shadowed locally
     (is (nil? zipmap))
     ;; referred ns, without alias
     (is (clojure.string/blank? ""))
     ;; referred ns, with alias
     (is (= my-set (set/intersection (hash-set 1 2 3) (hash-set 2 3 4))))))

(deftest display-namespaces-option-test
  (testing "macroexpand-1 expander with display-namespaces: qualified"
    (is+ {:status #{"done"}
          :expansion "(clojure.test/deftest\n  test-foo\n  (clojure.test/is (clojure.core/zero? 0))\n  (clojure.test/is\n    (clojure.core/nil? cider.nrepl.middleware.macroexpand-test/zipmap))\n  (clojure.test/is (clojure.string/blank? \"\"))\n  (clojure.test/is\n    (clojure.core/=\n      cider.nrepl.middleware.macroexpand-test/my-set\n      (clojure.set/intersection\n        (clojure.core/hash-set 1 2 3)\n        (clojure.core/hash-set 2 3 4)))))"}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :display-namespaces "qualified"})))

  (testing "qualified is the default display-namespaces"
    (is+ {:status #{"done"}
          :expansion "(clojure.test/deftest\n  test-foo\n  (clojure.test/is (clojure.core/zero? 0))\n  (clojure.test/is\n    (clojure.core/nil? cider.nrepl.middleware.macroexpand-test/zipmap))\n  (clojure.test/is (clojure.string/blank? \"\"))\n  (clojure.test/is\n    (clojure.core/=\n      cider.nrepl.middleware.macroexpand-test/my-set\n      (clojure.set/intersection\n        (clojure.core/hash-set 1 2 3)\n        (clojure.core/hash-set 2 3 4)))))"}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"})))

  (testing "macroexpand-1 expander with display-namespaces: none"
    (is+ {:status #{"done"}
          :expansion "(deftest\n  test-foo\n  (is (zero? 0))\n  (is (nil? zipmap))\n  (is (blank? \"\"))\n  (is (= my-set (intersection (hash-set 1 2 3) (hash-set 2 3 4)))))"}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :display-namespaces "none"})))

  (testing "macroexpand-1 expander with display-namespaces: tidy"
    (is+ {:status #{"done"}
          :expansion "(deftest\n  test-foo\n  (is (clojure.core/zero? 0))\n  (is (nil? zipmap))\n  (is (clojure.string/blank? \"\"))\n  (is (= my-set (set/intersection (hash-set 1 2 3) (hash-set 2 3 4)))))"}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand-1"
                           :code "(tidy-test-macro)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :display-namespaces "tidy"})))

  (testing "invalid display-namespaces"
    (is+ {:status #{"done" "macroexpand-error"}
          :err some?
          :ex some?
          :pp-stacktrace some?}
         (session/message {:op "cider/macroexpand"
                           :code "(defn x [] nil)"
                           :display-namespaces "foo"}))))

(deftest print-meta-option-test
  (testing "macroexpand-1 expander with print-meta: true"
    (is+ {:status #{"done"}
          :expansion "(def ^{:arglists (quote ([]))} x (fn ([] nil)))"}
         (session/message {:op "cider/macroexpand"
                           :expander "macroexpand-1"
                           :code "(defn x [] nil)"
                           :ns "cider.nrepl.middleware.macroexpand-test"
                           :display-namespaces "tidy"
                           :print-meta "true"}))))

(deftest print-mw-before-macroexpand-mw-test
  (testing "if print middleware ends up before macroexpand mw on the way up,
it doesn't mess up macroexpansion output"
    ;; Start a custom server
    (binding [session/*handler* (cider.nrepl/wrap-macroexpand
                                 (nrepl.middleware.session/session
                                  (nrepl.middleware.print/wrap-print
                                   (nrepl.middleware.interruptible-eval/interruptible-eval nil))))]
      (session/session-fixture
       (fn []
         (let [{:keys [expansion]} (session/message {:op "cider/macroexpand"
                                                     :expander "macroexpand-1"
                                                     :code (:expr code)
                                                     :display-namespaces "none"})]
           (is (= (:expanded-1 code) expansion)))

         (let [{:keys [expansion]} (session/message {:op "cider/macroexpand"
                                                     :expander "macroexpand"
                                                     :code (:expr code)
                                                     :display-namespaces "none"})]
           (is (= (:expanded code) expansion))))))))

(deftest deprecated-op-test
  (testing "Deprecated 'macroexpand' op still works"
    (is+ {:status #{"done"}
          :expansion (:expanded-1 code)}
         (session/message {:op "macroexpand"
                           :expander "macroexpand-1"
                           :code (:expr code)
                           :display-namespaces "none"}))))
