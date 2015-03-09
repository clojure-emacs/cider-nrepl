(ns cider.nrepl.middleware.undef-test
  (:require [cider.nrepl.middleware.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest undef
  (testing "undef undefines vars"
    (is (= ["#'user/x"]
           (:value (session/message {:op "eval"
                                     :code "(def x 1)"}))))
    (is (= ["#'user/x"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'user 'x)"}))))
    (is (= #{"done"}
           (:status (session/message {:op "undef"
                                      :ns "user"
                                      :symbol "x"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'user 'x)"}))))))
