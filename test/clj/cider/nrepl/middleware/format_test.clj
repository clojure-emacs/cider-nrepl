(ns cider.nrepl.middleware.format-test
  (:require [cider.nrepl.middleware.format :refer :all]
            [cider.nrepl.test-session :as session]
            [cider.nrepl.test-transport :refer :all]
            [clojure.test :refer :all]))

(def ugly-code
  "( let [x 3
       y 4]
  (+ (* x x
        )(* y y)
         ))")

(def formatted-code
  "(let [x 3
      y 4]
  (+ (* x x) (* y y)))")

(deftest test-format-code-op
  (let [transport (test-transport)]
    (format-code-reply {:transport transport :code ugly-code})
    (is (= (messages transport) [{:formatted-code formatted-code :status #{:done}}]))))

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

(deftest test-format-edn-op
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
    (let [{:keys [err status]} (session/message {:op "format-edn"
                                                 :edn unmatched-delimiter-edn-sample})]
      (is (= #{"edn-read-error" "done"} status))
      (is (.startsWith err "clojure.lang.ExceptionInfo: Unmatched delimiter"))))

  (testing "format-edn respects the :pprint-fn slot"
    (let [{:keys [formatted-edn status]} (session/message {:op "format-edn"
                                                           :edn "{:b 2 :c 3 :a 1}"
                                                           :pprint-fn "cider.nrepl.middleware.pprint/puget-pprint"})]
      (is (= "{:a 1, :b 2, :c 3}" formatted-edn))
      (is (= #{"done"} status)))))
