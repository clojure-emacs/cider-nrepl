(ns cider.nrepl.pprint-test
  (:require
   [cider.nrepl.pprint :as sut]
   [clojure.test :refer [deftest is testing]]))

(defn- render [f value opts]
  (let [writer (java.io.StringWriter.)]
    (f value writer opts)
    (str writer)))

(deftest pprint-lazy-output-test
  ;; #903: clojure.pprint binds *out* to the target writer while printing, so a
  ;; lazy seq whose realization writes to *out* gets its side-effect output
  ;; interleaved into - and corrupting - the printed value. orchard.pp prints to
  ;; the writer explicitly without rebinding *out*, so the two stay separate.
  (testing "a lazy seq's realization side effects don't leak into the printed value"
    (let [value-writer (java.io.StringWriter.)
          side-writer (java.io.StringWriter.)
          lazy (lazy-seq (do (print "SIDE-EFFECT") (cons 1 nil)))]
      (binding [*out* side-writer]
        (sut/pprint lazy value-writer {}))
      (is (= "(1)\n" (str value-writer)))
      (is (= "SIDE-EFFECT" (str side-writer))))))

(deftest pprint-options-test
  (testing ":right-margin controls wrapping"
    (is (= "[1 2 3]\n" (render sut/pprint [1 2 3] {})))
    (is (= "[1\n 2\n 3]\n" (render sut/pprint [1 2 3] {:right-margin 2}))))

  (testing ":length truncates long seqs"
    (is (= "(0 1 2 ...)\n" (render sut/pprint (range 100) {:length 3}))))

  (testing ":level truncates deep nesting"
    (is (= "[[#]]\n" (render sut/pprint [[[1]]] {:level 2})))))

(deftest clojure-pprint-still-available-test
  (testing "clojure-pprint keeps the clojure.pprint behaviour"
    (is (= "[1\n 2\n 3]" (render sut/clojure-pprint [1 2 3] {:right-margin 2})))))
