(ns cider.nrepl.middleware.format-test
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.format))

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
