(ns cider.nrepl.middleware.classpath-test
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.classpath))

(deftest test-classpath-op
  (let [transport (test-transport)]
    (classpath-reply {:transport transport})
    (is (= (messages transport) [{:classpath (classpath) :status #{:done}}]))))
