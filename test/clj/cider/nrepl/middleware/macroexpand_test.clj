(ns cider.nrepl.middleware.macroexpand-test
  (:refer-clojure :exclude [zero? zipmap])
  (:require [cider.nrepl.test-session :as session]
            [clojure.set :as set]
            [clojure.string]
            [clojure.test :refer :all]))

(use-fixtures :once session/session-fixture)

(def code
  {:expr         "(while (while 1))"
   :expanded     "(loop* [] (when (while 1) (recur)))"
   :expanded-1   "(loop [] (when (while 1) (recur)))"
   :expanded-all "(loop* [] (if (loop* [] (if 1 (do (recur)))) (do (recur))))"})

(deftest expander-option-test
  (testing "macroexpand-1 expander works"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code (:expr code)
                                                       :display-namespaces "none"})]
      (is (= (:expanded-1 code) expansion))
      (is (= #{"done"} status))))

  (testing "macroexpand expander works"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand"
                                                       :code (:expr code)
                                                       :display-namespaces "none"})]
      (is (= (:expanded code) expansion))
      (is (= #{"done"} status))))

  (testing "macroexpand-all expander works"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-all"
                                                       :code (:expr code)
                                                       :display-namespaces "none"})]
      (is (= (:expanded-all code) expansion))
      (is (= #{"done"} status))))

  (testing "macroexpand is the default expander"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :code (:expr code)
                                                       :display-namespaces "none"})]
      (is (= (:expanded code) expansion))
      (is (= #{"done"} status))))

  (testing "invalid expander"
    (let [{:keys [err ex status pp-stacktrace]} (session/message {:op "macroexpand"
                                                                  :expander "foo"
                                                                  :code "(defn x [] nil)"})]
      (is err)
      (is ex)
      (is (= #{"done" "macroexpand-error"} status))
      (is pp-stacktrace))))

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
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(tidy-test-macro)"
                                                       :ns "cider.nrepl.middleware.macroexpand-test"
                                                       :display-namespaces "qualified"})]
      (is (= "(clojure.test/deftest\n  test-foo\n  (clojure.test/is (clojure.core/zero? 0))\n  (clojure.test/is\n    (clojure.core/nil? cider.nrepl.middleware.macroexpand-test/zipmap))\n  (clojure.test/is (clojure.string/blank? \"\"))\n  (clojure.test/is\n    (clojure.core/=\n      cider.nrepl.middleware.macroexpand-test/my-set\n      (clojure.set/intersection\n        (clojure.core/hash-set 1 2 3)\n        (clojure.core/hash-set 2 3 4)))))"
             expansion))
      (is (= #{"done"} status))))

  (testing "qualified is the default display-namespaces"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(tidy-test-macro)"
                                                       :ns "cider.nrepl.middleware.macroexpand-test"})]
      (is (= "(clojure.test/deftest\n  test-foo\n  (clojure.test/is (clojure.core/zero? 0))\n  (clojure.test/is\n    (clojure.core/nil? cider.nrepl.middleware.macroexpand-test/zipmap))\n  (clojure.test/is (clojure.string/blank? \"\"))\n  (clojure.test/is\n    (clojure.core/=\n      cider.nrepl.middleware.macroexpand-test/my-set\n      (clojure.set/intersection\n        (clojure.core/hash-set 1 2 3)\n        (clojure.core/hash-set 2 3 4)))))"
             expansion))
      (is (= #{"done"} status))))

  (testing "macroexpand-1 expander with display-namespaces: none"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(tidy-test-macro)"
                                                       :ns "cider.nrepl.middleware.macroexpand-test"
                                                       :display-namespaces "none"})]
      (is (= "(deftest\n  test-foo\n  (is (zero? 0))\n  (is (nil? zipmap))\n  (is (blank? \"\"))\n  (is (= my-set (intersection (hash-set 1 2 3) (hash-set 2 3 4)))))"
             expansion))
      (is (= #{"done"} status))))

  (testing "macroexpand-1 expander with display-namespaces: tidy"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(tidy-test-macro)"
                                                       :ns "cider.nrepl.middleware.macroexpand-test"
                                                       :display-namespaces "tidy"})]
      (is (= "(deftest\n  test-foo\n  (is (clojure.core/zero? 0))\n  (is (nil? zipmap))\n  (is (clojure.string/blank? \"\"))\n  (is (= my-set (set/intersection (hash-set 1 2 3) (hash-set 2 3 4)))))"
             expansion))
      (is (= #{"done"} status))))

  (testing "invalid display-namespaces"
    (let [{:keys [err ex status pp-stacktrace]} (session/message {:op "macroexpand"
                                                                  :code "(defn x [] nil)"
                                                                  :display-namespaces "foo"})]
      (is err)
      (is ex)
      (is (= #{"done" "macroexpand-error"} status))
      (is pp-stacktrace))))

(deftest print-meta-option-test
  (testing "macroexpand-1 expander with print-meta: true"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(defn x [] nil)"
                                                       :ns "cider.nrepl.middleware.macroexpand-test"
                                                       :display-namespaces "tidy"
                                                       :print-meta "true"})]
      (is (= "(def ^{:arglists (quote ([]))} x (fn ([] nil)))" expansion))
      (is (= #{"done"} status)))))
