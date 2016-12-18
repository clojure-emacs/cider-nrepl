(ns cider.nrepl.middleware.util.inspect-test
  (:require [cider.nrepl.middleware.util.inspect :as i :refer [inspect-value]]
            [clojure.test :refer :all]))

(defprotocol IMyTestType
  (^String get-name [this]))

(deftype MyTestType [name]
  IMyTestType
  (get-name [this] name))

(defmethod inspect-value MyTestType [obj]
  (str "#<MyTestType " (get-name obj) ">"))

(deftest inspect-val-test
  (testing "inspect-value print types"
    (are [result form] (= result (inspect-value form))
      "1" 1
      "\"2\"" "2"
      ":foo" :foo
      ":abc/def" :abc/def
      "( :a :b :c )" '(:a :b :c)
      "[ 1 2 3 ]" [1 2 3]
      "{ :a 1, :b 2 }" {:a 1 :b 2}
      "#{ :a }" #{:a}
      "( 1 1 1 1 1 ... )" (repeat 1)
      "[ ( 1 1 1 1 1 ... ) ]" [(repeat 1)]
      "( 1 2 3 )" (lazy-seq '(1 2 3))
      "#<MyTestType test1>" (MyTestType. "test1"))))

(deftest inspect-path
  (testing "inspector keeps track of the path in the inspected structure"
    (let [t {:a (list 1 2 {:b {:c (vec (map (fn [x] {:foo (* x 10)}) (range 100)))}})
             :z 42}
          inspector (-> (i/start (i/fresh) t)
                        (i/down 1) (i/down 2)
                        (i/up) (i/up)
                        (i/down 1) (i/down 2)
                        (i/down 2)
                        (i/up)
                        (i/down 3)
                        (i/down 1) (i/down 2)
                        (i/down 1) (i/down 2)
                        (i/down 10)
                        (i/down 1) (i/down 1))]
      inspector
      (is (= '[:a (nth 2) :b :c (nth 9) (find :foo) first] (:path inspector)))
      (is (= '[:a (nth 2) :b :c (nth 9) (find :foo) first class]
             (:path (-> inspector (i/down 0)))))
      (is (= '[:a (nth 2) :b :c (nth 9) (find :foo) first class <unknown>]
             (:path (-> inspector (i/down 0) (i/down 1))))))))
