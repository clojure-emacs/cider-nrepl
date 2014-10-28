(ns cider.nrepl.middleware.trace-test
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.trace))

(deftest test-toogle-trace-op
  (let [transport (test-transport)]
    (toggle-trace {:transport transport :ns "clojure.core" :sym "map"})
    (is (= (messages transport) [{:value "map traced." :status #{:done}}]))
    (toggle-trace {:transport transport :ns "clojure.core" :sym "map"})
    (is (= (messages transport) [{:value "map traced." :status #{:done}}
                                 {:value "map untraced." :status #{:done}}]))))
