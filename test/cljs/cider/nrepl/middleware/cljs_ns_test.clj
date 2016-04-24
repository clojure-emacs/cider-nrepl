(ns cider.nrepl.middleware.cljs-ns-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each piggieback-fixture)

(deftest cljs-ns
  (testing "ns-list op"
    (let [{:keys [ns-list]} (session/message {:op "ns-list"})]
      (is (sequential? ns-list))
      (is (every? string? ns-list))))

  (testing "ns-vars op"
    (let [{:keys [ns-vars]} (session/message {:op "ns-vars"
                                              :ns "cljs.core"})]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))))

  (testing "ns-vars-with-meta op"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "ns-vars-with-meta"
                                               :ns "cljs.core"}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (= (:+ ns-vars-with-meta)
             {:arglists "(quote ([] [x] [x y] [x y & more]))"}))))

  (testing "ns-path op"
    (let [{:keys [path]} (session/message {:op "ns-path"
                                           :ns "cljs.core"})]
      (is (.endsWith path "cljs/core.cljs")))

    (let [{:keys [path]} (session/message {:op "ns-path"
                                           :ns "cljs.repl"})]
      (is (.endsWith path "cljs/repl.cljs")))))
