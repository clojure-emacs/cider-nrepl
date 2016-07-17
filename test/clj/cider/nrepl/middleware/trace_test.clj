(ns cider.nrepl.middleware.trace-test
  (:require [cider.nrepl.middleware.trace :refer :all]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]
            [cider.test-ns.first-test-ns]))

(use-fixtures :each session/session-fixture)

(deftest toggle-trace-var-test
  (testing "toggling"
    (is (= {:var-name "#'clojure.core/zipmap" :var-status "traced"}
           (toggle-trace-var {:ns "clojure.core" :sym "zipmap"})))
    (is (= {:var-name "#'clojure.core/zipmap" :var-status "untraced"}
           (toggle-trace-var {:ns "clojure.core" :sym "zipmap"}))))

  (testing "misses"
    (testing "toggle-trace-var-op unresolvable, should return `not-found`"
      (is (= {:var-status "not-found" :status #{:toggle-trace-error :done}}
             (toggle-trace-var {:ns "clojure.core" :sym "mappp"}))))

    (testing "toogle-trace-var-op not traceable var, should return `not-traceable`"
      (is (= {:var-name "#'clojure.core/and" :var-status "not-traceable"}
             (toggle-trace-var {:ns "clojure.core" :sym "and"}))))))

(deftest toggle-trace-ns-test
  (testing "toogling"
    (is (= {:ns-status "traced"}
           (toggle-trace-ns {:ns "clojure.core"})))
    (is (= {:ns-status "untraced"}
           (toggle-trace-ns {:ns "clojure.core"}))))

  (testing "toogle-trace-ns-op missing ns should return `not-found`"
    (is (= {:ns-status "not-found"}
           (toggle-trace-ns {:ns "clojure.corex"})))))

(deftest integration-tests-var
  (testing "toggling"
    (let [on  (session/message {:op "toggle-trace-var"
                                :ns "cider.test-ns.first-test-ns"
                                :sym "same-name-testing-function"})
          off (session/message {:op "toggle-trace-var"
                                :ns "cider.test-ns.first-test-ns"
                                :sym "same-name-testing-function"})]
      (is (= (:status on) (:status off) #{"done"}))
      (is (= (:var-name on) (:var-name off) "#'cider.test-ns.first-test-ns/same-name-testing-function"))
      (is (= (:var-status on) "traced"))
      (is (= (:var-status off) "untraced"))))

  (testing "unresolvable"
    (let [var-err (session/message {:op "toggle-trace-var"
                                    :ns "cider.test-ns.first-test-ns"
                                    :sym "missing"})
          ns-err  (session/message {:op "toggle-trace-var"
                                    :ns "cider.test-ns.no-such-ns"
                                    :sym "same-name-testing-function"})]
      (is (= (:status var-err) (:status ns-err) #{"toggle-trace-error" "done"}))
      (is (:var-status var-err) "not-found"))))

(deftest integration-test-ns
  (testing "toggling"
    (let [on  (session/message {:op "toggle-trace-ns"
                                :ns "cider.test-ns.first-test-ns"})
          off (session/message {:op "toggle-trace-ns"
                                :ns "cider.test-ns.first-test-ns"})]
      (is (= (:status on) (:status off) #{"done"}))
      (is (= (:ns-status on) "traced"))
      (is (= (:ns-status off) "untraced")))

    (let [ns-err (session/message {:op "toggle-trace-ns"
                                   :ns "cider.test-ns.missing"})]
      (is (= (:status ns-err)  #{"done"}))
      (is (= (:ns-status ns-err) "not-found")))))
