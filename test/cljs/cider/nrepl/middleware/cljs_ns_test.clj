(ns cider.nrepl.middleware.cljs-ns-test
  (:require
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :once piggieback-fixture)

(deftest cljs-ns-test
  (testing "ns-list op"
    (let [{:keys [ns-list]} (session/message {:op "ns-list"})]
      (is (sequential? ns-list))
      (is (every? string? ns-list))))

  (testing "ns-vars op"
    (let [{:keys [ns-vars]} (session/message {:op "ns-vars"
                                              :ns "cljs.core"})]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))
      (is (not (contains? (set ns-vars) "maybe-warn")))))

  (testing "ns-vars op with private? var-query"
    (let [{:keys [ns-vars]} (session/message {:op "ns-vars"
                                              :ns "cljs.core"
                                              :var-query {:private? 1}})]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))
      (is (contains? (set ns-vars) "maybe-warn"))))

  (testing "ns-vars-with-meta op"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "ns-vars-with-meta"
                                               :ns "cljs.core"}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (= (:+ ns-vars-with-meta)
             {:arglists "(quote ([] [x] [x y] [x y & more]))"
              :doc "\"Returns the sum of nums. (+) returns 0.\""}))
      (is (not (contains? ns-vars-with-meta :maybe-warn)))))

  (testing "ns-vars-with-meta op with private? var-query"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "ns-vars-with-meta"
                                               :ns "cljs.core"
                                               :var-query {:private? 1}}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (= (:maybe-warn ns-vars-with-meta)
             {:arglists "(quote ([e]))"}))))

  (testing "ns-path op"
    (let [{:keys [^String path]} (session/message {:op "ns-path"
                                                   :ns "cljs.core"})]
      (is (.endsWith path "cljs/core.cljs")))

    (let [{:keys [^String path]} (session/message {:op "ns-path"
                                                   :ns "cljs.repl"})]
      (is (.endsWith path "cljs/repl.cljs")))))
