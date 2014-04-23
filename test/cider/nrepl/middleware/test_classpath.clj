(ns cider.nrepl.middleware.test-classpath
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.classpath))

(deftest test-classpath-op
  (let [transport (test-transport)]
    (classpath-reply {:transport transport})
    (is (= (messages transport) [{:value (classpath)} {:status #{:done}}]))))
