(ns cider.nrepl.middleware.util.error-handling-test
  (:require
   [cider.nrepl.middleware.util.error-handling :as err]
   [cider.nrepl.test-transport :as tt]
   [clojure.test :refer :all]))

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

(deftest bencode-test
  (testing "shallow-bencodable?"
    (let [bencodable? #'err/shallow-bencodable?]
      (is (bencodable? nil))
      (is (bencodable? 1))
      (is (not (bencodable? 1.2)))
      (is (not (bencodable? 1/2)))
      (is (bencodable? (byte-array [(byte 0x43) (byte 0x6c) (byte 0x6f)])))
      (is (bencodable? (boolean-array [false true false])))
      (is (bencodable? "string"))
      (is (bencodable? :kw))
      (is (bencodable? 'x))
      (is (bencodable? {:a :map}))
      (is (bencodable? [:a :vector 1 2 3]))
      (is (bencodable? '(:a :list)))
      (is (bencodable? #{:a :set}))
      (is (not (bencodable? *ns*)))
      (is (bencodable? [*ns*]) "This should pass since the function only does a shallow test.")))

  (testing "deep-bencodable-or-fail"
    (let [deep-bencodable? #'err/deep-bencodable-or-fail]
      (is (deep-bencodable? nil))
      (is (deep-bencodable? 1))
      (is (thrown? IllegalArgumentException (deep-bencodable? 1.2)))
      (is (thrown? IllegalArgumentException (deep-bencodable? 1/2)))
      (is (deep-bencodable? (byte-array [(byte 0x43) (byte 0x6c) (byte 0x6f)])))
      (is (deep-bencodable? (boolean-array [false true false])))
      (is (deep-bencodable? "string"))
      (is (deep-bencodable? :kw))
      (is (deep-bencodable? 'x))
      (is (deep-bencodable? {:a :map}))
      (is (deep-bencodable? [:a :vector 1 2 3]))
      (is (deep-bencodable? [:a :vector 1 {:a :map} 2 '(:a :list) 3]))
      (is (deep-bencodable? '(:a :list)))
      (is (deep-bencodable? #{:a :set}))
      (is (thrown? IllegalArgumentException (deep-bencodable? *ns*)))
      (is (thrown? IllegalArgumentException (deep-bencodable? [*ns*])))
      (is (thrown? IllegalArgumentException (deep-bencodable? [1 2 3 4 *ns*])))
      (is (deep-bencodable? [:a :vector 1 {:a :map} 2 '(:a {:bad-map *ns*} :list) 3])
          "Should pass since *ns* is inside a quoted list and doesn't get evaluated")
      (is (thrown? IllegalArgumentException (deep-bencodable? [:a :vector 1 {:a :map} 2 [:sub :vec :bad *ns*] '(:a :list) 3]))))))
