(ns cider.nrepl.middleware.pprint-test
  (:require [cider.nrepl.middleware.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :once session/session-fixture)

(deftest test-wrap-pprint
  (let [code "[1 2 3 4 5 6 7 8 9 0]"]
    (testing "wrap-pprint does not interfere with normal :eval requests"
      (is (= [code]
             (:value (session/message {:op :eval
                                       :code code}))))

      (is (= nil
             (:out (session/message {:op :eval
                                     :code code})))))

    (testing "wrap-pprint does not clobber the :value slot"
      (is (= [code]
             (:value (session/message {:op :eval
                                       :code code
                                       :pprint "true"})))))

    (testing "wrap-pprint ensures that :eval requests are pretty-printed"
      (is (= "[1 2 3 4 5 6 7 8 9 0]\n"
             (:out (session/message {:op :eval
                                     :code code
                                     :pprint "true"})))))

    (testing "wrap-pprint respects the :right-margin slot"
      (is (= "[1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n 0]\n"
             (:out (session/message {:op :eval
                                     :code code
                                     :pprint "true"
                                     :right-margin 10})))))))
