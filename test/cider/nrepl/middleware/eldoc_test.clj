(ns cider.nrepl.middleware.eldoc-test
  (:require [clojure.test :refer :all]
            [cider.nrepl.middleware.eldoc :as eldoc]))

(def test-info {:arglists '([x] [x y])})

(def test-info-candidates {:candidates
                           '{X {:arglists ([x])}
                             Y {:arglists ([x] [x y z])}
                             Z {:arglists ([])}}})

(deftest test-extract-eldoc
  (is (= (eldoc/extract-eldoc test-info) '([x] [x y])))
  (is (= (eldoc/extract-eldoc test-info-candidates)
         '([] [x] [x y z]))))

(deftest test-format-eldoc
  (is (= (eldoc/format-eldoc (eldoc/extract-eldoc test-info)) '(["x"] ["x" "y"])))
  (is (= (eldoc/format-eldoc (eldoc/extract-eldoc test-info-candidates))
         '([] ["x"] ["x" "y" "z"]))))

(deftest test-eldoc
  (is (eldoc/eldoc {:ns "clojure.core" :symbol "map"}))
  (is (eldoc/eldoc {:ns "clojure.core" :symbol ".toString"}))
  (is (not (eldoc/eldoc {:ns "clojure.core" :symbol (gensym "non-existing")}))))
