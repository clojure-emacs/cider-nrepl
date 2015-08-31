(ns cider.nrepl.middleware.cljs-pprint-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :once piggieback-fixture)

(deftest cljs-pprint
  (testing "pretty printing works"
    (is (= "[1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n 0]\n"
           (:pprint-out (session/message {:op :eval
                                          :code "[1 2 3 4 5 6 7 8 9 0]"
                                          :pprint "true"
                                          :print-right-margin 10})))))

  (testing "wrap-pprint does not escape special characters when printing strings"
    (is (= "abc\ndef\tghi\n"
           (:pprint-out (session/message {:op :eval
                                          :code "\"abc\ndef\tghi\""
                                          :pprint "true"}))))))
