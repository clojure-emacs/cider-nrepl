(ns cider.nrepl.middleware.resource-test
  (:require [cider.nrepl.middleware.resource :refer :all]
            [cider.nrepl.middleware.test-transport :refer :all]
            [clojure.test :refer :all]))

(deftest test-resource-op
  (let [transport (test-transport)]
    (resource-reply {:transport transport :name "test.txt"})
    (is (= (messages transport) [{:resource-path (resource-path "test.txt") :status #{:done}}]))))
