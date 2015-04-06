(ns cider.nrepl.middleware.ns-test
  (:require [cider.nrepl.middleware.ns :refer [ns-list-clj ns-vars-clj]]
            [cider.nrepl.test-session :as session]
            [cider.nrepl.test-transport :refer [messages test-transport]]
            [clojure.test :refer :all]))

(deftest test-toogle-ns-list
  (is (= (count (all-ns)) (count (ns-list-clj)))))

(deftest test-toogle-ns-vars
  (let [ns "clojure.core"]
    (is (= (count (ns-publics (symbol ns))) (count (ns-vars-clj ns))))))

;; integration tests

(use-fixtures :each session/session-fixture)

(deftest ns-list-integration-test
  (let [ns-list (:ns-list (session/message {:op "ns-list"}))]
    (is (sequential? ns-list))
    (is (every? string? ns-list))))

(deftest ns-vars-integration-test
  (let [ns-vars (:ns-vars (session/message {:op "ns-vars"
                                            :ns "clojure.walk"}))]
    (is (sequential? ns-vars))
    (is (every? string? ns-vars))))
