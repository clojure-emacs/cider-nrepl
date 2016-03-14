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

    (testing "wrap-pprint respects the :print-length slot"
      (is (= "[1 2 ...]\n"
             (:pprint-out (session/message {:op :eval
                                            :code code
                                            :pprint "true"
                                            :print-length 2})))))

    (testing "wrap-pprint respects the :print-level slot"
      (is (= "[0 [1 #]]\n"
             (:pprint-out (session/message {:op :eval
                                            :code "[0 [1 [2]]]"
                                            :pprint "true"
                                            :print-level 2})))))

    (testing "wrap-pprint respects the :print-right-margin slot"
      (is (= "[1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n 0]\n"
             (:pprint-out (session/message {:op :eval
                                            :code code
                                            :pprint "true"
                                            :print-right-margin 10})))))

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
                        {:status ["done"]}]))))

  (testing "fipp-pprint works"
    (let [message {:op :eval
                   :code "{nil [nil nil nil #{nil} nil nil nil]}"
                   :pprint "true"
                   :pprint-fn "cider.nrepl.middleware.pprint/fipp-pprint"
                   :print-right-margin 10}]
      (is (= "{nil\n [nil\n  nil\n  nil\n  #{nil}\n  nil\n  nil\n  nil]}\n"
             (:pprint-out (session/message (dissoc message :pprint-fn)))))
      (is (= "{nil [nil\n      nil\n      nil\n      #{nil}\n      nil\n      nil\n      nil]}\n"
             (:pprint-out (session/message message)))))

    (let [message {:op :eval
                   :code "{nil [nil nil nil #{nil} nil nil nil]}"
                   :pprint "true"
                   :pprint-fn "cider.nrepl.middleware.pprint/fipp-pprint"}]
      (is (= "{nil [nil nil nil #{nil} nil nil nil]}\n"
             (:pprint-out (session/message message))))))

  (testing "fipp-pprint respects the :print-meta slot"
    (is (= "^{:a :b}\n{}\n"
           (:pprint-out (session/message {:op :eval
                                          :code "^{:a :b} {}"
                                          :pprint "true"
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
                                          :pprint "true"
                                          :pprint-fn "cider.nrepl.middleware.pprint/fipp-pprint"
                                          :print-meta "true"})))))

  (testing "non-resolvable pprint-fn"
    (testing "non-existing ns"
      (let [response (session/message {:op :eval
                                       :code "nil"
                                       :pprint "true"
                                       :pprint-fn "never-used-ns-example/pprint"})]
        (is (= (:status response) #{"eval-error" "done"}))
        (is (= (:ex response) "class java.lang.IllegalArgumentException"))
        (is (= (:root-ex response) "class java.lang.IllegalArgumentException"))
        (is (.startsWith (:err response) "IllegalArgumentException No such namespace: nev"))))

    (testing "non-existing Var"
      (let [response (session/message {:op :eval
                                       :code "nil"
                                       :pprint "true"
                                       :pprint-fn "clojure.core/never-used-pprint-example"})]
        (is (= (:status response) #{"eval-error" "done"}))
        (is (= (:ex response) "class java.lang.IllegalArgumentException"))
        (is (= (:root-ex response) "class java.lang.IllegalArgumentException"))
        (is (.startsWith (:err response) "IllegalArgumentException clojure.core/never-used"))))

    (testing "nil input"
      (let [response (session/message {:op :eval
                                       :code "nil"
                                       :pprint "true"
                                       :pprint-fn nil})]
        (is (= (:status response) #{"eval-error" "done"}))
        (is (= (:ex response) "class java.lang.NullPointerException"))
        (is (= (:root-ex response) "class java.lang.NullPointerException"))
        (is (.startsWith (:err response) "NullPointerException   clojure.lang.Var.find"))))))
