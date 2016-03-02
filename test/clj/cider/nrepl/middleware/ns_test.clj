(ns cider.nrepl.middleware.ns-test
  (:require [cider.nrepl.middleware.ns
             :refer [ns-vars-clj
                     ns-list-vars-by-name]]
            [cider.nrepl.test-session :as session]
            [cider.nrepl.test-transport :refer [messages test-transport]]
            [clojure.test :refer :all]))

(deftest test-toogle-ns-vars
  (let [ns "clojure.core"]
    (is (= (count (ns-publics (symbol ns))) (count (ns-vars-clj ns))))))

;; integration tests

(use-fixtures :each session/session-fixture)

(deftest ns-list-integration-test
  (let [ns-list (:ns-list (session/message {:op "ns-list"}))]
    (is (sequential? ns-list))
    (is (every? string? ns-list))
    (testing "Removal of namespaces created by source rewriting"
      (is (not-any? #(or (.startsWith % "deps.")
                         (.startsWith % "mranderson")
                         (.startsWith % "eastwood.copieddeps"))
                    ns-list)))))

(deftest ns-vars-integration-test
  (let [ns-vars (:ns-vars (session/message {:op "ns-vars"
                                            :ns "clojure.walk"}))]
    (is (sequential? ns-vars))
    (is (every? string? ns-vars))))

(deftest ns-path-integration-test
  (let [ns-path (:path (session/message {:op "ns-path"
                                         :ns "cider.nrepl.middleware.ns"}))
        core-path (:path (session/message {:op "ns-path"
                                           :ns "clojure.core"}))]
    (is (.endsWith ns-path "cider/nrepl/middleware/ns.clj"))
    (is (.endsWith core-path "clojure/core.clj"))))

(deftest test-ns-list-vars-by-name
  (is (= (first (ns-list-vars-by-name 'test-ns-list-vars-by-name))
         #'cider.nrepl.middleware.ns-test/test-ns-list-vars-by-name))
  (is (= (count (ns-list-vars-by-name 'test-ns-list-vars-by-name)) 1))
  (is (not (seq (ns-list-vars-by-name 'all-your-base-are-belong-to-us)))))
