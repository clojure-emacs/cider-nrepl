(ns cider.nrepl.middleware.cljs-macroexpand-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :once piggieback-fixture)

(deftest cljs-macroexpansion
  (testing "macroexpand-1 expander works"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "nil"
                                                       :ns "cljs.core"})]
      (is (= "nil" expansion))
      (is (= #{"done"} status)))

    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(pos? 1)"
                                                       :ns "cljs.core"})]
      (is (= "(cljs.core/> 1 0)" expansion))
      (is (= #{"done"} status)))

    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(pos? (pos? 1))"
                                                       :ns "cljs.core"})]
      (is (= "(cljs.core/> (pos? 1) 0)" expansion))
      (is (= #{"done"} status))))

  (testing "macroexpand expander works"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand"
                                                       :code "nil"
                                                       :ns "cljs.core"})]
      (is (= "nil" expansion))
      (is (= #{"done"} status)))

    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand"
                                                       :code "(pos? 1)"
                                                       :ns "cljs.core"})]
      (is (= "(js* \"(~{} > ~{})\" 1 0)" expansion))
      (is (= #{"done"} status)))

    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand"
                                                       :code "(pos? (pos? 1))"
                                                       :ns "cljs.core"})]
      (is (= "(js* \"(~{} > ~{})\" (pos? 1) 0)" expansion))
      (is (= #{"done"} status))))

  (testing "macroexpand-all expander works"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-all"
                                                       :code "nil"
                                                       :ns "cljs.core"})]
      (is (= "nil" expansion))
      (is (= #{"done"} status)))

    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-all"
                                                       :code "(pos? 1)"
                                                       :ns "cljs.core"})]
      (is (= "(js* \"(~{} > ~{})\" 1 0)" expansion))
      (is (= #{"done"} status)))

    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-all"
                                                       :code "(pos? (pos? 1))"
                                                       :ns "cljs.core"})]
      (is (= "(js* \"(~{} > ~{})\" (js* \"(~{} > ~{})\" 1 0) 0)" expansion))
      (is (= #{"done"} status))))

  (testing "invalid expander"
    (let [{:keys [err ex status] :as response} (session/message {:op "macroexpand"
                                                                 :expander "foo"
                                                                 :code "(pos? 1)"
                                                                 :ns "cljs.core"})]
      (is err)
      (is ex)
      (is (= #{"done" "macroexpand-error"} status))))

  (testing "display-namespaces: qualified"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(defn x [] (clojure.set/union))"
                                                       :ns "clojure.data"
                                                       :display-namespaces "qualified"})]
      (is (= "(def x (cljs.core/fn ([] (clojure.set/union))))" expansion))
      (is (= #{"done"} status))))

  (testing "display-namespaces: none"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand-1"
                                                       :code "(defn x [] (clojure.set/union))"
                                                       :ns "clojure.data"
                                                       :display-namespaces "none"})]
      (is (= "(def x (fn ([] (union))))" expansion))
      (is (= #{"done"} status)))

    (let [{:keys [expansion status] :as response} (session/message {:op "macroexpand"
                                                                    :expander "macroexpand-1"
                                                                    :code "(defn x [] (.log js/console 1))"
                                                                    :ns "clojure.data"
                                                                    :display-namespaces "none"})]
      (is (= "(def x (fn ([] (.log js/console 1))))" expansion))
      (is (= #{"done"} status))))

  (testing "display-namespaces: tidy"
    (let [{:keys [expansion status] :as response} (session/message {:op "macroexpand"
                                                                    :expander "macroexpand-1"
                                                                    :code "(defn x [] (clojure.set/union))"
                                                                    :ns "clojure.data"
                                                                    :display-namespaces "tidy"})]
      (is (= "(def x (fn ([] (set/union))))" expansion))
      (is (= #{"done"} status))))

  (testing "invalid display-namespaces"
    (let [{:keys [err ex status]} (session/message {:op "macroexpand"
                                                    :expander "macroexpand-1"
                                                    :code "(defn x [] nil)"
                                                    :display-namespaces "foo"})]
      (is err)
      (is ex)
      (is (= #{"done" "macroexpand-error"} status))))

  (testing "print-meta"
    (let [{:keys [expansion status]} (session/message {:op "macroexpand"
                                                       :expander "macroexpand"
                                                       :code "(defn- x [] nil)"
                                                       :print-meta "true"})]
      (is (= "(def ^{:private true, :arglists (quote ([]))} x\n (clojure.core/fn ([] nil)))"
             expansion))
      (is (= #{"done"} status)))))
