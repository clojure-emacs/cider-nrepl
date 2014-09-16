(ns cider.nrepl.middleware.resource-test
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.resource))

(deftest test-resource-op
  (let [transport (test-transport)]
    (resource-reply {:transport transport :name "test.txt"})
    (is (= (messages transport) [{:resource-path (resource-path "test.txt") :status #{:done}}]))))
