(ns cider.nrepl.middleware.cljs-ns-test
  (:require
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [clojure.set :as set]
   [clojure.test :refer :all]))

(use-fixtures :once piggieback-fixture)

(deftest cljs-ns-test
  (testing "ns-list op"
    (let [{:keys [ns-list]} (session/message {:op "cider/ns-list"})]
      (is (sequential? ns-list))
      (is (every? string? ns-list))))

  (testing "ns-vars op"
    (let [{:keys [ns-vars]} (session/message {:op "cider/ns-vars"
                                              :ns "cljs.core"})]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))
      (is (not (contains? (set ns-vars) "maybe-warn")))))

  (testing "ns-vars op with private? var-query"
    (let [{:keys [ns-vars]} (session/message {:op "cider/ns-vars"
                                              :ns "cljs.core"
                                              :var-query {:private? 1}})]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))
      (is (contains? (set ns-vars) "maybe-warn"))))

  (testing "ns-vars-with-meta op"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "cider/ns-vars-with-meta"
                                               :ns "cljs.core"}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (= (:+ ns-vars-with-meta)
             {:arglists "(quote ([] [x] [x y] [x y & more]))"
              :doc "\"Returns the sum of nums. (+) returns 0.\""}))
      (is (not (contains? ns-vars-with-meta :maybe-warn)))))

  (testing "ns-vars-with-meta op with private? var-query"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "cider/ns-vars-with-meta"
                                               :ns "cljs.core"
                                               :var-query {:private? 1}}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (= (:maybe-warn ns-vars-with-meta)
             {:arglists "(quote ([e]))"}))))

  (testing "ns-path op"
    (let [{:keys [^String path ^String url]} (session/message {:op "cider/ns-path"
                                                               :ns "cljs.core"})]
      (is (.endsWith path "cljs/core.cljs"))
      (is (not= path url))
      (is (.startsWith url "jar:file:/")))

    (let [{:keys [^String path]} (session/message {:op "cider/ns-path"
                                                   :ns "cljs.repl"})]
      (is (.endsWith path "cljs/repl.cljs")))))

(deftest cljs-resolve-missing-test
  (let [resolve-missing (fn [msg]
                          (let [{:keys [candidates status]}
                                (session/message (merge {:op "cider/resolve-missing"} msg))]
                            (is (= #{"done"} status))
                            (set candidates)))]
    (testing "Namespaces with a public var of that name"
      (is (set/subset? #{{:name "clojure.set" :type "ns"}
                         {:name "clojure.string" :type "ns"}}
                       (resolve-missing {:symbol "join"})))
      (is (contains? (resolve-missing {:symbol "str/join"})
                     {:name "clojure.string" :type "ns"})))

    (testing "cljs.core is left out"
      (is (not-any? #(= "cljs.core" (:name %)) (resolve-missing {:symbol "map"}))))

    (testing "Macros"
      (is (contains? (resolve-missing {:symbol "doc"})
                     {:name "cljs.repl" :type "macro"})))

    (testing "A symbol the namespace can already use gets nothing"
      (is (empty? (resolve-missing {:symbol "doc" :ns "cljs.user"})))
      (is (empty? (resolve-missing {:symbol "string/join" :ns "cljs.pprint"})))
      (is (empty? (resolve-missing {:symbol "map" :ns "cljs.pprint"})))
      (is (empty? (resolve-missing {:symbol "cljs.core/map" :ns "cljs.user"})))
      (is (empty? (resolve-missing {:symbol "clojure.core/map" :ns "cljs.user"})))
      (is (empty? (resolve-missing {:symbol "pprint" :ns "cljs.pprint"})))
      (is (contains? (resolve-missing {:symbol "str/join" :ns "cljs.pprint"})
                     {:name "clojure.string" :type "ns"})))

    (testing "A fully qualified symbol leaves no choice"
      (is (= #{{:name "clojure.string" :type "ns"}}
             (resolve-missing {:symbol "clojure.string/join"})))
      ;; Not even when the namespace lacks the var
      (is (empty? (resolve-missing {:symbol "cljs.user/join" :ns "cljs.user"}))))))

(deftest cljs-resolve-alias-test
  (let [resolve-alias (fn [msg]
                        (:candidates
                         (session/message (merge {:op "cider/resolve-alias" :platform "cljs"} msg))))]
    (testing "An alias the namespace has already, or that's something else, gets nothing"
      (is (empty? (resolve-alias {:alias "string" :ns "cljs.pprint"})))
      (is (empty? (resolve-alias {:alias "cljs.pprint" :ns "cljs.user"})))
      (is (empty? (resolve-alias {:alias "js" :preferred ["clojure.string"]}))))

    (testing "An :as-alias or a macro alias counts"
      (session/message {:op "eval"
                        :code "(ns cider.resolve-alias-test (:require [clojure.string :as-alias zs]) (:require-macros [cljs.test :as zt]))"})
      (is (empty? (resolve-alias {:alias "zs" :ns "cider.resolve-alias-test" :preferred ["clojure.string"]})))
      (is (empty? (resolve-alias {:alias "zt" :ns "cider.resolve-alias-test" :preferred ["cljs.test"]})))
      (is (= [{:ns "clojure.string" :source "preferred"}]
             (resolve-alias {:alias "zq" :ns "cider.resolve-alias-test" :preferred ["clojure.string"]}))
          "unlike an alias it doesn't have"))

    (testing "A namespace that isn't there for ClojureScript isn't offered"
      (is (empty? (resolve-alias {:alias "zzjio" :preferred ["clojure.java.io"]})))
      (is (= [{:ns "clojure.pprint" :source "preferred"}]
             (resolve-alias {:alias "zzpp" :preferred ["clojure.pprint"]}))
          "clojure.pprint is cljs.pprint in ClojureScript"))))
