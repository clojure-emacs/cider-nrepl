(ns cider.nrepl.middleware.util.inspect-test
  (:require [cider.nrepl.middleware.util.inspect :refer (inspect-value)]
            [clojure.test :refer :all]))

(defprotocol IMyTestType
   (^String get-name [this]))

(deftype MyTestType [name]
  IMyTestType
  (get-name [this] name))

(defmethod inspect-value MyTestType [obj]
  (str "#<MyTestType " (get-name obj) ">"))

(deftest inspect-val
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
      "#<MyTestType test1>" (MyTestType. "test1"))))

