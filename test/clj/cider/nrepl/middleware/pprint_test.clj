(ns cider.nrepl.middleware.pprint-test
  (:require
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :once session/session-fixture)

#_(deftest wrap-pprint-custom-pprint-fn-test
  (testing "fipp-pprint respects the :print-meta slot"
    (is (= "^{:a :b}\n{}\n"
           (:pprint-out (session/message {:op :eval
                                          :code "^{:a :b} {}"
                                          :pprint-fn "cider.nrepl.middleware.pprint/fipp-pprint"
                                          :print-meta "true"})))))

  (testing "puget-pprint works"
    (is (= "{:a 1, :b 2, :c 3, :d 4, :e 5}\n"
           (:pprint-out (session/message {:op :eval
                                          :code "{:b 2 :e 5 :a 1 :d 4 :c 3}"
                                          :pprint "true"
                                          :pprint-fn "cider.nrepl.middleware.pprint/puget-pprint"}))))

    (is (= "{:a 1,\n :b 2,\n :c 3,\n :d 4,\n :e 5}\n"
           (:pprint-out (session/message {:op :eval
                                          :code "{:b 2 :e 5 :a 1 :d 4 :c 3}"
                                          :pprint "true"
                                          :pprint-fn "cider.nrepl.middleware.pprint/puget-pprint"
                                          :print-right-margin 4})))))

  (testing "puget-pprint respects the :print-length slot"
    (is (= "(0 1 ...)\n"
           (:pprint-out (session/message {:op :eval
                                          :code "(range 100)"
                                          :pprint "true"
                                          :pprint-fn "cider.nrepl.middleware.pprint/puget-pprint"
                                          :print-length 2})))))

  (testing "puget-pprint respects the :print-meta slot"
    (is (= "^{:a :b}\n{}\n"
           (:pprint-out (session/message {:op :eval
                                          :code "^{:a :b} {}"
                                          :pprint-fn "cider.nrepl.middleware.pprint/fipp-pprint"
                                          :print-meta "true"}))))))
