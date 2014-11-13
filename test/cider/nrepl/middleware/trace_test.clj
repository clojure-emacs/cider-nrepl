(ns cider.nrepl.middleware.trace-test
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.trace))

(deftest test-toogle-trace-op
  (let [transport (test-transport)]
    (toggle-trace {:transport transport :ns "clojure.core" :sym "map"})
    (is (= (messages transport) [{:var-name "#'clojure.core/map" :var-status "traced" :status #{:done}}]))
    (toggle-trace {:transport transport :ns "clojure.core" :sym "map"})
    (is (= (messages transport) [{:var-name "#'clojure.core/map" :var-status "traced" :status #{:done}}
                                 {:var-name "#'clojure.core/map" :var-status "untraced" :status #{:done}}]))))

(deftest test-toogle-trace-op-missing-var
  (let [transport (test-transport)]
    (toggle-trace {:transport transport :ns "clojure.core" :sym "mappp"})
    (is (= (messages transport) [{:var-status "not-found" :status #{:toggle-trace-error :done}}]))))

(deftest test-toogle-trace-op-not-traceable-var
  (let [transport (test-transport)]
    (toggle-trace {:transport transport :ns "clojure.core" :sym "and"})
    (is (= (messages transport) [{:var-name "#'clojure.core/and" :var-status "not-traceable" :status #{:done}}]))))
