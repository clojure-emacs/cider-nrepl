(ns cider.nrepl.middleware.test.extensions-test
  (:require [clojure.test :refer :all]
            [cider.nrepl.middleware.test.extensions :as extensions]))

(deftest =-body-test
  (testing "Only evalulates expected form once"
    (let [x (eval
             `(let [~'x (atom 0)]
                ~(extensions/=-body "" '(swap! x inc) '(1))
                (deref ~'x)))]
      (is (= 1 x)))))
