(ns cider.nrepl.middleware.util.error-handling-test
  (:require [clojure.test :refer :all]
            [cider.nrepl.middleware.util.error-handling :as err]
            [cider.nrepl.test-transport :as tt]))

(deftest op-handler-test
  (is (= {:id 5 :status #{:test :done}}  (err/op-handler :test {:id 5})))
  (is (= {:id 5 :status #{:more :than :one :done}}  (err/op-handler [:more :than :one] {:id 5})))
  (is (= {:id 5 :anon 6 :status #{:done}} (err/op-handler (fn [m] {:anon (inc (:id m))}) {:id 5})))
  (is (= {:id 5 :inline :reply :status #{:done}} (err/op-handler {:inline :reply} {:id 5}))))

(deftest error-handler-test
  (let [e (Exception. "testing")]
    (is (err/error-handler :done {:id 5} e))
    (is (err/error-handler [:more :than :one] {:id 5} e))
    (is (err/error-handler (fn [m e] {:anon (inc (:id m))}) {:id 5} e))
    (is (err/error-handler {:inline :reply} {:id 5} e))))
