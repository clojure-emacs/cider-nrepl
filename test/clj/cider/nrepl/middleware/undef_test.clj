(ns cider.nrepl.middleware.undef-test
  (:require
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest undef-var-test
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
                                      :sym "x"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'user 'x)"})))))
  (testing "undef undefines vars in other namespaces"
    (is (= #{"done"}
           (:status (session/message {:op   "eval"
                                      :code "(do (ns other.ns) (in-ns 'user) (require '[other.ns :as other]))"}))))
    (is (= ["#'other.ns/x"]
           (:value (session/message {:op   "eval"
                                     :code "(do (in-ns 'other.ns) (def x 1) (in-ns 'user) (ns-resolve 'other.ns 'x))"}))))
    (is (= #{"done"}
           (:status (session/message {:op     "undef"
                                      :ns     "other.ns"
                                      :sym "x"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'other.ns 'x)"})))))
  (testing "undef takes fully qualified symbols"
    (is (= ["#'other.ns/x"]
           (:value (session/message {:op   "eval"
                                     :code "(do (in-ns 'other.ns) (def x 1) (in-ns 'user) (ns-resolve 'other.ns 'x))"}))))
    (is (= #{"done"}
           (:status (session/message {:op     "undef"
                                      :ns     "user"
                                      :sym "other.ns/x"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'other.ns 'x)"})))))
  (testing "undef resolves namespace aliases in fully qualified symbols"
    (is (= ["#'other.ns/x"]
           (:value (session/message {:op   "eval"
                                     :code "(do (in-ns 'other.ns) (def x 1) (in-ns 'user) (ns-resolve 'other.ns 'x))"}))))
    (is (= #{"done"}
           (:status (session/message {:op     "undef"
                                      :ns     "user"
                                      :sym "other/x"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'other.ns 'x)"}))))))

(deftest undef-alias-test
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
                                      :sym "postwalk"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'user 'postwalk)"}))))))

(deftest undef-undefined-test
  (testing "undef does not throw for aliases or vars that are not defined"
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'user 'x)"}))))
    (is (= #{"done"}
           (:status (session/message {:op "undef"
                                      :ns "user"
                                      :sym "x"}))))))

(deftest undef-exceptions-test
  (testing "undef throws for non-existent namespaces"
    (is (= #{"done" "undef-error"}
           (:status (session/message {:op "undef"
                                      :ns "abc"
                                      :sym "x"})))))

  (testing "undef throws with missing arguments"
    (is (= #{"done" "undef-error"}
           (:status (session/message {:op "undef"}))
           (:status (session/message {:op "undef" :ns "user"}))
           (:status (session/message {:op "undef" :sym "x"})))))

  (testing "error handling"
    (let [response (session/message {:op "undef"})]
      (is (:pp-stacktrace response))
      (is (:err response))
      (is (:ex response)))))

(deftest undef-all-test
  (testing "undef-all undefines all vars in namespace"
    (is (= #{"done"}
           (:status (session/message {:op "eval"
                                      :code "(do (ns other.ns (:require [clojure.walk :as walk :refer [postwalk]])))"}))))
    (is (= ["#'clojure.core/assoc"]
           (:value (session/message {:op "eval"
                                     :code "(do (in-ns 'user) (ns-resolve 'other.ns 'assoc))"}))))
    (is (= ["#'clojure.walk/postwalk"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'other.ns 'postwalk)"}))))
    (is (= #{"done"}
           (:status (session/message {:op "undef-all"
                                      :ns "other.ns"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'other.ns 'assoc)"}))))
    (is (= ["nil"]
           (:value (session/message {:op "eval"
                                     :code "(ns-resolve 'other.ns 'postwalk)"}))))
    (is (= ["{}"]
           (:value (session/message {:op "eval"
                                     :code "(ns-aliases 'other.ns)"}))))))
