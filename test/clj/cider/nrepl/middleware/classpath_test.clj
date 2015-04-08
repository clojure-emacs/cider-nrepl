(ns cider.nrepl.middleware.classpath-test
  (:require [cider.nrepl.middleware.classpath :refer :all]
            [cider.nrepl.test-transport :refer :all]
            [clojure.test :refer :all]))

(deftest test-classpath-op
  (let [transport (test-transport)]
    (classpath-reply {:transport transport})
    (is (= (messages transport) [{:classpath (classpath) :status #{:done}}]))))
