(ns cider.nrepl.middleware.test.extensions-test
  (:require
   [cider.nrepl.middleware.test.extensions :as extensions]
   [lambdaisland.deep-diff :as dd]
   [clojure.test :as test :refer :all]))

(deftest =-body-test
  (testing "Adds diff output"
    (let [report (eval
                  `(let [~'r (atom nil)]
                     (with-redefs [test/do-report (fn [r#]
                                                    (reset! ~'r r#))]
                       ~(extensions/=-body "" {:foo :bar} [{:foo :baz}])
                       (deref ~'r))))]
      (is (= 1 (count (:diffs report))))))
  (testing "Only evalulates expected form once"
    (let [x (eval
             `(let [~'x (atom 0)]
                ~(extensions/=-body "" '(swap! x inc) '(1))
                (deref ~'x)))]
      (is (= 1 x)))))
