(ns cider.nrepl.middleware.format-test
  (:require [cider.nrepl.middleware.format :refer :all]
            [cider.nrepl.middleware.test-transport :refer :all]
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

(def ugly-edn
  "{    :a 1
   :b 2
       :c [0 1 2 3]

          :d [
    [0 1 2 3

      ]
        [0
           1 2 3]]
}")

(def formatted-edn
  "{:a 1, :b 2, :c [0 1 2 3], :d [[0 1 2 3] [0 1 2 3]]}")

(def formatted-with-margin-edn
  "{:a 1,
 :b 2,
 :c [0 1 2 3],
 :d
 [[0 1 2 3]
  [0 1 2 3]]}")

(def ugly-edn-forms
  "[
0 1 2 3]

   [0        1 2     3]

[0 1 2

  3

]")

(def formatted-edn-forms
  "[0 1 2 3]
[0 1 2 3]
[0 1 2 3]")

(def formatted-with-margin-edn-forms
  "[0
 1
 2
 3]
[0
 1
 2
 3]
[0
 1
 2
 3]")

(def unmatched-delimiter-edn
  ")")

(deftest test-format-edn-op
  (let [transport (test-transport)]
    (format-edn-reply {:transport transport :edn ugly-edn})
    (format-edn-reply {:transport transport :edn ugly-edn :right-margin 20})
    (format-edn-reply {:transport transport :edn ugly-edn-forms})
    (format-edn-reply {:transport transport :edn ugly-edn-forms :right-margin 4})
    (is (= (messages transport) [{:formatted-edn formatted-edn :status #{:done}}
                                 {:formatted-edn formatted-with-margin-edn :status #{:done}}
                                 {:formatted-edn formatted-edn-forms :status #{:done}}
                                 {:formatted-edn formatted-with-margin-edn-forms :status #{:done}}])))
  (let [transport (test-transport)
        _ (format-edn-reply {:transport transport :edn unmatched-delimiter-edn})
        response (first (messages transport))]
    (is (= (:status response) #{:edn-read-error :done}))
    (is (.startsWith (:err response) "clojure.lang.ExceptionInfo: Unmatched delimiter"))))
