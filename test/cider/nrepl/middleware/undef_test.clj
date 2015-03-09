(ns cider.nrepl.middleware.undef-test
  (:require [cider.nrepl.middleware.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest undef-var
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

(deftest undef-alias
  (testing "undef undefines aliases"
    (is (= ["#'clojure.walk/postwalk"]
           (:value (do
                     (session/message {:op "eval"
                                       :code "(require '[clojure.walk :refer [postwalk]])"})
                     (session/message {:op "eval"
                                       :code "(ns-resolve 'user 'postwalk)"})))))
    (is (= #{"done"}
           (:status (session/message {:op "undef"
                                      :ns "user"
                                      :symbol "postwalk"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'user 'postwalk)"}))))))

(deftest undef-undefined
  (testing "undef does not throw for aliases or vars that are not defined"
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'user 'x)"}))))
    (is (= #{"done"}
           (:status (session/message {:op "undef"
                                      :ns "user"
                                      :symbol "x"}))))))

(deftest undef-exceptions
  (testing "undef throws for non-existent namespaces"
    (is (= #{"done" "undef-error"}
           (:status (session/message {:op "undef"
                                      :ns "abc"
                                      :symbol "x"})))))

  (testing "undef throws with missing arguments"
    (is (= #{"done" "undef-error"}
           (:status (session/message {:op "undef"}))
           (:status (session/message {:op "undef" :ns "user"}))
           (:status (session/message {:op "undef" :symbol "x"}))))))
