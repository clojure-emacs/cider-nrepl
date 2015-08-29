(ns cider.nrepl.middleware.pprint-test
  (:require [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :once session/session-fixture)

(deftest test-wrap-pprint
  (let [code "[1 2 3 4 5 6 7 8 9 0]"]
    (testing "wrap-pprint does not interfere with normal :eval requests"
      (is (= [code]
             (:value (session/message {:op :eval
                                       :code code}))))

      (is (= nil
             (:pprint-out (session/message {:op :eval
                                            :code code})))))

    (testing "wrap-pprint elides the :value slot from responses"
      (let [response (session/message {:op :eval
                                       :code code
                                       :pprint "true"})]
        (is (not (contains? response :value)))))

    (testing "wrap-pprint ensures that :eval requests are pretty-printed"
      (is (= "[1 2 3 4 5 6 7 8 9 0]\n"
             (:pprint-out (session/message {:op :eval
                                            :code code
                                            :pprint "true"})))))

    (testing "wrap-pprint respects the :right-margin slot"
      (is (= "[1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n 0]\n"
             (:pprint-out (session/message {:op :eval
                                            :code code
                                            :pprint "true"
                                            :right-margin 10})))))

    (testing "wrap-pprint does not escape special characters when printing strings"
      (is (= "abc\ndef\tghi\n"
             (:pprint-out (session/message {:op :eval
                                            :code "\"abc\ndef\tghi\""
                                            :pprint "true"}))))))

  (testing "wrap-pprint correctly sends a sentinel value after each evaluation"
    (let [message {:op :eval
                   :code "[1 2 3] [4 5 6]"
                   :pprint "true"}
          responses (->> (session/message message false)
                         (map #(dissoc % :id :session :ns :state))
                         (filter not-empty))]
      (is (= responses [{:pprint-out "[1 2 3]\n"}
                        {:pprint-sentinel {}}
                        {:pprint-out "[4 5 6]\n"}
                        {:pprint-sentinel {}}
                        {:status ["done"]}])))))
