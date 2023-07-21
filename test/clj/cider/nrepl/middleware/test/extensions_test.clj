(ns cider.nrepl.middleware.test.extensions-test
  (:require
   [cider.nrepl.middleware.test.extensions :as extensions]
   [clojure.test :refer [are deftest is testing]]))

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
             (- (swap! a inc) 9)))))
  (testing ":actual is a scalar for (= x y) (i.e. arity 2)
 and an informative list for (= x y z) (i.e. arity 3+)"
    (are [subject args expected] (= expected
                                    (let [proof (atom nil)]
                                      (with-redefs [clojure.test/do-report
                                                    (fn [m]
                                                      (reset! proof m))]
                                        (eval (extensions/=-body "_" subject args))
                                        @proof)))
      1 [1]   '{:expected 1, :actual 1, :message "_", :type :pass}
      1 [2]   '{:expected 1, :actual 2, :message "_", :type :fail}
      1 [2 3] '{:expected 1, :actual (not (= 1 2 3)), :message "_", :type :fail}))

  (testing ":diffs are only included for diffable objects"
    (are [subject args expected] (= expected
                                    (:diffs (extensions/maybe-assoc-diffs {} subject args)))
      1       [1]               nil
      1       [2 3]             nil
      ;; one different, diffable value:
      {:a :b} [{:a :c}]         '[[{:a :c} ({:a :b} {:a :c} nil)]]
      ;; two different, diffable values:
      {:a :b} [{:a :c} {:a :d}] '[[{:a :c} ({:a :b} {:a :c} nil)]
                                  [{:a :d} ({:a :b} {:a :d} nil)]]
      ;; one different, diffable value at first position:
      {:a :b} [{:a :c} {:a :b}] '[[{:a :c} ({:a :b} {:a :c} nil)]
                                  ;; note that this diff is useless, but we include it for consistency for clients:
                                  [{:a :b} [nil nil {:a :b}]]]
      ;; one different, diffable value at last position:
      {:a :b} [{:a :b} {:a :d}] '[[{:a :b} [nil nil {:a :b}]] ;; (another 'useless' diff, see previous note)
                                  [{:a :d} ({:a :b} {:a :d} nil)]])))
