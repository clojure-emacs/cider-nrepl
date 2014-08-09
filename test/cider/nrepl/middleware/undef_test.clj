(ns cider.nrepl.middleware.undef-test
  (:require
   [clojure.test :refer :all]
   [cider.nrepl.middleware.test-transport :refer [messages test-transport]]
   [cider.nrepl.middleware.undef :refer [undef-reply]]))

(def x 1)

(deftest test-toogle-undef-op
  (let [transport (test-transport)]
    (is (ns-resolve 'cider.nrepl.middleware.undef-test 'x))
    (undef-reply {:transport transport :ns "cider.nrepl.middleware.undef-test" :symbol "x"})
    (is (= [{:status #{:done}}] (messages transport)))
    (is (nil? (ns-resolve 'cider.nrepl.middleware.undef-test 'x)))))
