(ns cider.nrepl.middleware.util.storage-test
  (:require [cider.nrepl.middleware.util.storage :as c-store]
            [clojure.test :refer :all]))

(use-fixtures :each (fn [f] (c-store/refresh!) (f)))

(deftest test-single-add
  (is (= 1 (c-store/add! :a 1))))

(deftest multiple-add
  (is (= 1 (c-store/add! :a 1)))
  (is (= 2 (c-store/add! :b 2)))
  (is (= 3 (c-store/add! :c 3))))

(deftest test-empty-query
  (is (nil? (c-store/query! :a))))

(deftest test-miss-query
  (c-store/add! :b 2)
  (is (nil? (c-store/query! :a))))

(deftest test-hit-query
  (c-store/add! :a 1)
  (is (= 1 (c-store/query! :a)))
  (is (nil? (c-store/query! :a))))

(deftest test-over-capacity
  (c-store/add! :a 1)
  (c-store/add! :b 2)
  (c-store/add! :c 3)
  (c-store/add! :d 4)
  (c-store/add! :e 5)
  (c-store/add! :f 6)
  (c-store/add! :g 7)
  (c-store/add! :h 8)
  (c-store/add! :i 9)
  (c-store/add! :j 10)
  (c-store/add! :k 11)
  (is (nil? (c-store/query! :a)))
  (is (= 11 (c-store/query! :k))))

(deftest test-refresh
  (c-store/add! :a 1)
  (c-store/add! :b 2)
  (c-store/add! :c 3)
  (c-store/add! :d 4)
  (c-store/add! :e 5)
  (c-store/add! :f 6)
  (c-store/add! :g 7)
  (c-store/add! :h 8)
  (c-store/add! :i 9)
  (c-store/add! :j 10)
  (c-store/add! :k 11)
  (c-store/refresh!)
  (is (nil? (c-store/query! :a)))
  (is (nil? (c-store/query! :e)))
  (is (nil? (c-store/query! :k))))
