(ns cider.nrepl.middleware.format-test
  (:require [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(def ugly-code-sample
  "( let [x 3
       y 4]
  (+ (* x x
        )(* y y)
         ))")

(def formatted-code-sample
  "(let [x 3
      y 4]
  (+ (* x x) (* y y)))")

(def ugly-edn-sample
  "{    :a 1
   :b 2
       :c [0 1 2 3]

          :d [
    [0 1 2 3

      ]
        [0
           1 2 3]]
}")

(def formatted-edn-sample
  "{:a 1, :b 2, :c [0 1 2 3], :d [[0 1 2 3] [0 1 2 3]]}")

(def ugly-edn-forms-sample
  "[
0 1 2 3]

   [0        1 2     3]

[0 1 2

  3

]")

(def formatted-edn-forms-sample
  "[0 1 2 3]
[0 1 2 3]
[0 1 2 3]")

(def unmatched-delimiter-edn-sample
  ")")

(use-fixtures :once session/session-fixture)

(deftest format-code-op-test
  (testing "format-code works"
    (let [{:keys [formatted-code status]} (session/message {:op "format-code"
                                                            :code ugly-code-sample})]
      (is (= #{"done"} status))
      (is (= formatted-code-sample formatted-code))))

  (testing "format-code op error handling"
    (let [{:keys [status err ex]} (session/message {:op "format-code"
                                                    :code "*/*/*!~v"})]
      (is (= #{"format-code-error" "done"} status))
      (is (.startsWith err "clojure.lang.ExceptionInfo: Invalid"))
      (is (= ex "class clojure.lang.ExceptionInfo")))))

(deftest format-edn-op-test
  (testing "format-edn works"
    (let [{:keys [formatted-edn status]} (session/message {:op "format-edn"
                                                           :edn ugly-edn-sample})]
      (is (= formatted-edn-sample formatted-edn))
      (is (= #{"done"} status))))

  (testing "format-edn works for multiple forms"
    (let [{:keys [formatted-edn status]} (session/message {:op "format-edn"
                                                           :edn ugly-edn-forms-sample})]
      (is (= formatted-edn-forms-sample formatted-edn))
      (is (= #{"done"} status))))

  (testing "format-edn returns an error if the given EDN is malformed"
    (let [{:keys [err status] :as response} (session/message {:op "format-edn"
                                                              :edn unmatched-delimiter-edn-sample})]
      (is (= #{"format-edn-error" "done"} status))
      (is (.startsWith err "clojure.lang.ExceptionInfo: Unmatched delimiter"))
      (is (:pp-stacktrace response))))

  (testing "format-edn respects the :print-right-margin slot"
    (let [wide-edn-sample     "[1 2 3 4 5 6       7 8     9    0]"
          normal-reply        (session/message {:op  "format-edn" :edn wide-edn-sample})
          narrow-margin-reply (session/message {:op "format-edn"
                                                :edn wide-edn-sample
                                                :print-right-margin 10})]
      (is (= #{"done"} (:status normal-reply)))
      (is (= "[1 2 3 4 5 6 7 8 9 0]" (:formatted-edn normal-reply)))
      (is (= #{"done"} (:status narrow-margin-reply)))
      (is (= "[1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n 0]" (:formatted-edn narrow-margin-reply)))))

  (testing "format-edn respects the :pprint-fn slot"
    (let [{:keys [formatted-edn status]} (session/message {:op "format-edn"
                                                           :edn "{:b 2 :c 3 :a 1}"
                                                           :pprint-fn "cider.nrepl.middleware.pprint/puget-pprint"})]
      (is (= "{:a 1, :b 2, :c 3}" formatted-edn))
      (is (= #{"done"} status))))

  (testing "format-edn returns an error if the :pprint-fn is unresolvable"
    (let [{:keys [err ex status] :as response} (session/message {:op "format-edn"
                                                                 :edn "{:b 2 :c 3 :a 1}"
                                                                 :pprint-fn "fake.nrepl.middleware.pprint/puget-pprint"})]
      (is (.startsWith err "java.lang.IllegalArgumentException: No such namespace: fa"))
      (is (= "class java.lang.IllegalArgumentException" ex))
      (is (= #{"done" "format-edn-error"} status))
      (is (:pp-stacktrace response)))))
