(ns cider.nrepl.middleware.trace-test
  (:require [cider.nrepl.middleware.test-transport :refer :all]
            [cider.nrepl.middleware.trace :refer :all]
            [clojure.test :refer :all]))

(deftest test-toogle-trace-var-op
  (let [transport (test-transport)]
    (toggle-trace-var {:transport transport :ns "clojure.core" :sym "map"})
    (is (= (messages transport) [{:var-name "#'clojure.core/map" :var-status "traced" :status #{:done}}]))
    (toggle-trace-var {:transport transport :ns "clojure.core" :sym "map"})
    (is (= (messages transport) [{:var-name "#'clojure.core/map" :var-status "traced" :status #{:done}}
                                 {:var-name "#'clojure.core/map" :var-status "untraced" :status #{:done}}]))))

(deftest test-toogle-trace-var-op-missing-var
  (let [transport (test-transport)]
    (toggle-trace-var {:transport transport :ns "clojure.core" :sym "mappp"})
    (is (= (messages transport) [{:var-status "not-found" :status #{:toggle-trace-error :done}}]))))

(deftest test-toogle-trace-var-op-not-traceable-var
  (let [transport (test-transport)]
    (toggle-trace-var {:transport transport :ns "clojure.core" :sym "and"})
    (is (= (messages transport) [{:var-name "#'clojure.core/and" :var-status "not-traceable" :status #{:done}}]))))

(deftest test-toogle-trace-ns-op
  (let [transport (test-transport)]
    (toggle-trace-ns {:transport transport :ns "clojure.core"})
    (is (= (messages transport) [{:ns-status "traced" :status #{:done}}]))
    (toggle-trace-ns {:transport transport :ns "clojure.core"})
    (is (= (messages transport) [{:ns-status "traced" :status #{:done}}
                                 {:ns-status "untraced" :status #{:done}}]))))

(deftest test-toogle-trace-ns-op-missing-ns
  (let [transport (test-transport)]
    (toggle-trace-ns {:transport transport :ns "clojure.corex"})
    (is (= (messages transport) [{:ns-status "not-found" :status #{:toggle-trace-error :done}}]))))
