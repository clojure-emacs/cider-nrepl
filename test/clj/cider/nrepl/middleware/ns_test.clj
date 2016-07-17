(ns cider.nrepl.middleware.ns-test
  (:require [cider.nrepl.middleware.ns
             :refer [ns-vars-clj
                     ns-list-vars-by-name] :as cider-ns]
            [cider.nrepl.test-session :as session]
            [cider.nrepl.test-transport :refer [messages test-transport]]
            [cider.test-ns first-test-ns second-test-ns third-test-ns]
            [clojure.test :refer :all]))

(deftest toogle-ns-vars-test
  (let [ns "clojure.core"]
    (is (= (count (ns-publics (symbol ns))) (count (ns-vars-clj ns))))))

;; integration tests

(use-fixtures :each session/session-fixture)

(deftest ns-list-integration-test
  (testing "Basic checks"
    (let [ns-list (:ns-list (session/message {:op "ns-list"}))]
      (is (sequential? ns-list))
      (is (every? string? ns-list))))

  (testing "Removal of namespaces created by source rewriting"
    (let [ns-list (:ns-list (session/message {:op "ns-list"}))]
      (is (not-any? #(or (.startsWith % "deps.")
                         (.startsWith % "mranderson")
                         (.startsWith % "eastwood.copieddeps"))
                    ns-list))))

  (testing "Removal of namespaces with `filter-regexps`"
    (let [ns-list (:ns-list (session/message {:op "ns-list"
                                              :filter-regexps [".*nrepl"]}))]
      (is (not-any? #(re-find #".*nrepl" %) ns-list)))))

(deftest ns-list-vars-by-name-integration-test
  (let [response (session/message {:op "ns-list-vars-by-name"
                                   :name "same-name-testing-function"})
        var-list (read-string (:var-list response))]
    (is (= (set var-list)
           #{'(var cider.test-ns.first-test-ns/same-name-testing-function)
             '(var cider.test-ns.second-test-ns/same-name-testing-function)
             '(var cider.test-ns.third-test-ns/same-name-testing-function)}))))

(deftest ns-vars-integration-test
  (let [ns-vars (:ns-vars (session/message {:op "ns-vars"
                                            :ns "clojure.walk"}))]
    (is (sequential? ns-vars))
    (is (every? string? ns-vars))))

(deftest ns-vars-with-meta-integration-test
  (let [ns-vars-with-meta (:ns-vars-with-meta
                           (session/message {:op "ns-vars-with-meta"
                                             :ns "clojure.core"}))]
    (is (every? (comp map? second) ns-vars-with-meta))
    (is (= (:+ ns-vars-with-meta)
           {:arglists "([] [x] [x y] [x y & more])"
            :doc "\"Returns the sum of nums. (+) returns 0. Does not auto-promote\\n  longs, will throw on overflow. See also: +'\""}))
    (is (= (:doseq ns-vars-with-meta)
           {:arglists "([seq-exprs & body])"
            :macro "true"
            :doc "\"Repeatedly executes body (presumably for side-effects) with\\n  bindings and filtering as provided by \\\"for\\\".  Does not retain\\n  the head of the sequence. Returns nil.\""}))
    (is (= (:*ns* ns-vars-with-meta)
           {:doc "\"A clojure.lang.Namespace object representing the current namespace.\""}))))

(deftest ns-path-integration-test
  (let [ns-path (:path (session/message {:op "ns-path"
                                         :ns "cider.nrepl.middleware.ns"}))
        core-path (:path (session/message {:op "ns-path"
                                           :ns "clojure.core"}))]
    (is (.endsWith ns-path "cider/nrepl/middleware/ns.clj"))
    (is (.endsWith core-path "clojure/core.clj"))))

(deftest ns-load-all-integration-test
  (let [loaded-ns (:loaded-ns (session/message {:op "ns-load-all"}))]
    (is (sequential? loaded-ns))
    (is (every? string? loaded-ns))))

(deftest ns-list-vars-by-name-test
  (is (= (first (ns-list-vars-by-name 'ns-list-vars-by-name-test))
         #'cider.nrepl.middleware.ns-test/ns-list-vars-by-name-test))
  (is (= (count (ns-list-vars-by-name 'ns-list-vars-by-name-test)) 1))
  (is (not (seq (ns-list-vars-by-name 'all-your-base-are-belong-to-us)))))

(deftest error-handling-test
  (testing "ns-list op error handling"
    (with-redefs [cider-ns/ns-list (fn [& _] (throw (Exception. "ns-list error")))]
      (let [response (session/message {:op "ns-list"})]
        (is (.startsWith (:err response) "java.lang.Exception: ns-list error"))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"ns-list-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-list-vars-by-name op error handling"
    (with-redefs [cider-ns/ns-list-vars-by-name (fn [& _] (throw (Exception. "ns-list-vars-by-name error")))]
      (let [response (session/message {:op "ns-list-vars-by-name"
                                       :name "testing-function"})]
        (is (.startsWith (:err response) "java.lang.Exception: ns-list-vars-by-name error"))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"ns-list-vars-by-name-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-vars op error handling"
    (with-redefs [cider-ns/ns-vars (fn [& _] (throw (Exception. "ns-vars error")))]
      (let [response (session/message {:op "ns-vars"
                                       :name "testing-function"})]
        (is (.startsWith (:err response) "java.lang.Exception: ns-vars error"))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"ns-vars-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-path op error handling"
    (with-redefs [cider-ns/ns-path (fn [& _] (throw (Exception. "ns-path error")))]
      (let [response (session/message {:op "ns-path"
                                       :name "testing-function"})]
        (is (.startsWith (:err response) "java.lang.Exception: ns-path error"))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"ns-path-error" "done"}))
        (is (:pp-stacktrace response))))))
