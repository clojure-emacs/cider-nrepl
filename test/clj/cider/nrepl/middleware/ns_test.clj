(ns cider.nrepl.middleware.ns-test
  (:require
   [cider.nrepl.middleware.ns :refer [ns-vars-clj ns-list-vars-by-name] :as cider-ns]
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
    (let [ns-list (:ns-list (session/message {:op "cider/ns-list"}))]
      (is (sequential? ns-list))
      (is (every? string? ns-list))))

  (testing "Removal of namespaces created by source rewriting"
    (let [ns-list (:ns-list (session/message {:op "cider/ns-list"}))]
      (is (not-any? #(or (.startsWith ^String % "deps.")
                         (.startsWith ^String % "mranderson")
                         (.startsWith ^String % "eastwood.copieddeps"))
                    ns-list))))

  (testing "Removal of namespaces with `filter-regexps`"
    (let [ns-list (:ns-list (session/message {:op "cider/ns-list"
                                              :filter-regexps [".*nrepl"]}))]
      (is (not-any? #(re-find #".*nrepl" %) ns-list)))))

(deftest ns-list-vars-by-name-integration-test
  (let [response (session/message {:op "cider/ns-list-vars-by-name"
                                   :name "same-name-testing-function"})
        var-list (read-string (:var-list response))]
    (is (= (set var-list)
           #{'(var cider.test-ns.first-test-ns/same-name-testing-function)
             '(var cider.test-ns.second-test-ns/same-name-testing-function)
             '(var cider.test-ns.third-test-ns/same-name-testing-function)}))))

(deftest ns-vars-integration-test
  (let [ns-vars (:ns-vars (session/message {:op "cider/ns-vars"
                                            :ns "clojure.walk"}))]
    (is (sequential? ns-vars))
    (is (every? string? ns-vars))))

(deftest ns-vars-including-privates-test
  (testing "Without private vars"
    (let [ns-vars (:ns-vars (session/message {:op "cider/ns-vars"
                                              :ns "clojure.core"}))]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))
      (is (nil? (some #(= "is-annotation?" %) ns-vars)))))

  (testing "Including private vars"
    (let [ns-vars (:ns-vars (session/message {:op "cider/ns-vars"
                                              :ns "clojure.core"
                                              :var-query {:private? 1}}))]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))
      (is (some #(= "is-annotation?" %) ns-vars)))))

(deftest ns-vars-with-meta-integration-test
  (let [ns-vars-with-meta (:ns-vars-with-meta
                           (session/message {:op "cider/ns-vars-with-meta"
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

(deftest ns-vars-with-meta-including-privates-test
  (testing "Without private vars"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "cider/ns-vars-with-meta"
                                               :ns "clojure.core"}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (nil? (:is-annotation? ns-vars-with-meta)))))

  (testing "Including private vars"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "cider/ns-vars-with-meta"
                                               :ns "clojure.core"
                                               :var-query {:private? 1}}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (= (:is-annotation? ns-vars-with-meta)
             {:arglists "([c])"})))))

(deftest ns-path-integration-test
  (let [{ns-path :path
         ns-url :url} (session/message {:op "cider/ns-path"
                                        :ns "cider.nrepl.middleware.ns"})
        {core-path :path
         core-url :url} (session/message {:op "cider/ns-path"
                                          :ns "clojure.core"})]
    (is (.endsWith ^String ns-path "cider/nrepl/middleware/ns.clj"))
    (is (.endsWith ^String core-path "clojure/core.clj"))
    (is (.startsWith ^String core-path "jar:"))
    (is (= ns-path ns-url))
    (is (= core-path core-url))))

(deftest ns-load-all-integration-test
  (let [loaded-ns (:loaded-ns (session/message {:op "cider/ns-load-all"}))]
    (is (sequential? loaded-ns))
    (is (every? string? loaded-ns))))

(deftest ns-list-vars-by-name-test
  (is (= (first (ns-list-vars-by-name 'ns-list-vars-by-name-test))
         #'cider.nrepl.middleware.ns-test/ns-list-vars-by-name-test))
  (is (= (count (ns-list-vars-by-name 'ns-list-vars-by-name-test)) 1))
  (is (not (seq (ns-list-vars-by-name 'all-your-base-are-belong-to-us)))))

(deftest ns-aliases-integration-test
  (let [aliases (:ns-aliases (session/message {:op "cider/ns-aliases"
                                               :ns "cider.nrepl.middleware.ns-test"}))]
    (is (map? aliases))
    (is (= (:cider-ns aliases) "cider.nrepl.middleware.ns"))))

(deftest error-handling-test
  (testing "ns-list op error handling"
    (with-redefs [cider-ns/ns-list (fn [& _] (throw (Exception. "ns-list error")))]
      (let [response (session/message {:op "cider/ns-list"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-list error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-list-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-list-vars-by-name op error handling"
    (with-redefs [cider-ns/ns-list-vars-by-name (fn [& _] (throw (Exception. "ns-list-vars-by-name error")))]
      (let [response (session/message {:op "cider/ns-list-vars-by-name"
                                       :name "testing-function"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-list-vars-by-name error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-list-vars-by-name-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-vars op error handling"
    (with-redefs [cider-ns/ns-vars (fn [& _] (throw (Exception. "ns-vars error")))]
      (let [response (session/message {:op "cider/ns-vars"
                                       :name "testing-function"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-vars error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-vars-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-path op error handling"
    (with-redefs [cider-ns/ns-path (fn [& _] (throw (Exception. "ns-path error")))]
      (let [response (session/message {:op "cider/ns-path"
                                       :name "testing-function"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-path error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-path-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-aliases op error handling"
    (with-redefs [cider-ns/ns-aliases (fn [& _] (throw (Exception. "ns-aliases error")))]
      (let [response (session/message {:op "cider/ns-aliases" :name "testing-function"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-aliases error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-aliases-error" "done"}))
        (is (:pp-stacktrace response))))))

(deftest deprecated-ops-test
  (testing "Deprecated 'ns-list' op still works"
    (let [ns-list (:ns-list (session/message {:op "ns-list"}))]
      (is (sequential? ns-list))
      (is (every? string? ns-list))))

  (testing "Deprecated 'ns-vars' op still works"
    (let [ns-vars (:ns-vars (session/message {:op "ns-vars"
                                              :ns "clojure.walk"}))]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))))

  (testing "Deprecated 'ns-path' op still works"
    (let [{ns-path :path} (session/message {:op "ns-path"
                                            :ns "cider.nrepl.middleware.ns"})]
      (is (.endsWith ^String ns-path "cider/nrepl/middleware/ns.clj"))))

  (testing "Deprecated 'ns-aliases' op still works"
    (let [aliases (:ns-aliases (session/message {:op "ns-aliases"
                                                 :ns "cider.nrepl.middleware.ns-test"}))]
      (is (map? aliases)))))
