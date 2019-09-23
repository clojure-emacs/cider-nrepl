(ns cider.nrepl.middleware.test.extensions-test
  (:require
   [cider.nrepl.middleware.test.extensions :as extensions]
   [clojure.test :refer :all]))

(deftest =-body-test
  (testing "Only evalulates expected form once"
    (let [x (eval
             `(let [~'x (atom 0)]
                ~(extensions/=-body "" '(swap! x inc) '(1))
                (deref ~'x)))]
      (is (= 1 x))))
  (testing "Evaluates forms in the right order"
    (let [a (atom 0)]
      (is (= (- (swap! a inc) 1)
             (- (swap! a inc) 2)
             (- (swap! a inc) 3)
             (- (swap! a inc) 4)
             (- (swap! a inc) 5)
             (- (swap! a inc) 6)
             (- (swap! a inc) 7)
             (- (swap! a inc) 8)
             (- (swap! a inc) 9))))))
