(ns cider.nrepl.middleware.inspect-test
  (:require [cider.nrepl.middleware.util.inspect :as inspect]
            [cider.nrepl.middleware.test-session :as session]
            [clojure.test :refer :all]))

(def nil-result ["(\"nil\" (:newline))"])

(def var-result ["(\"Class\" \": \" (:value \"clojure.lang.Var\" 0) (:newline) \"Meta Information: \" (:newline) \"  \" (:value \":ns\" 1) \" = \" (:value \"clojure.core\" 2) (:newline) \"  \" (:value \":name\" 3) \" = \" (:value \"*assert*\" 4) (:newline) \"Value: \" (:value \"true\" 5))"])

(def code "(sorted-map :a {:b 1} :c \"a\" :d 'e :f [2 3])")

(def eval-result (eval (read-string code)))

(def inspect-result ["(\"Class\" \": \" (:value \"clojure.lang.PersistentTreeMap\" 0) (:newline) \"Contents: \" (:newline) \"  \" \"0\" \". \" (:value \"[ :a { :b 1 } ]\" 1) (:newline) \"  \" \"1\" \". \" (:value \"[ :c \\\"a\\\" ]\" 2) (:newline) \"  \" \"2\" \". \" (:value \"[ :d e ]\" 3) (:newline) \"  \" \"3\" \". \" (:value \"[ :f [ 2 3 ] ]\" 4) (:newline))"])

(def push-result ["(\"Class\" \": \" (:value \"clojure.lang.PersistentTreeMap$BlackVal\" 0) (:newline) \"Contents: \" (:newline) \"  \" \"0\" \". \" (:value \":a\" 1) (:newline) \"  \" \"1\" \". \" (:value \"{ :b 1 }\" 2) (:newline))"])

(defn inspect
  [value]
  (inspect/start (inspect/fresh) value))

(defn render
  [inspector]
  (vector (pr-str (:rendered inspector))))

(deftest nil-test
  (testing "nil renders correctly"
    (is (= nil-result
           (-> nil
               inspect
               render)))))

(deftest pop-empty-test
  (testing "popping an empty inspector renders nil"
    (is (= nil-result
           (-> (inspect/fresh)
               inspect/up
               render)))))

(deftest pop-empty-idempotent-test
  (testing "popping an empty inspector is idempotent"
    (is (= nil-result
           (-> (inspect/fresh)
               inspect/up
               inspect/up
               render)))))

(deftest push-empty-test
  (testing "pushing an empty inspector index renders nil"
    (is (= nil-result
           (-> (inspect/fresh)
               (inspect/down 1)
               render)))))

(deftest push-empty-idempotent-test
  (testing "pushing an empty inspector index is idempotent"
    (is (= nil-result
           (-> (inspect/fresh)
               (inspect/down 1)
               (inspect/down 1)
               render)))))

(deftest inspect-var-test
  (testing "rendering a var"
    (is (= var-result
           (-> #'*assert*
               inspect
               render)))))

(deftest inspect-expr-test
  (testing "rendering an expr"
    (is (= inspect-result
           (-> eval-result
               inspect
               render)))))

(deftest push-test
  (testing "pushing a rendered expr inspector idx"
    (is (= push-result
           (-> eval-result
               inspect
               (inspect/down 1)
               render)))))

(deftest pop-test
  (testing "popping a rendered expr inspector"
    (is (= inspect-result
           (-> eval-result
               inspect
               (inspect/down 1)
               inspect/up
               render)))))

;; integration tests

(use-fixtures :each session/session-fixture)

(deftest nil-integration-test
  (testing "nil renders correctly"
    (is (= nil-result
           (:value (session/message {:op "eval"
                                     :inspect "true"
                                     :code "nil"}))))))

(deftest pop-empty-integration-test
  (testing "popping an empty inspector renders nil"
    (is (= nil-result
           (:value (session/message {:op "inspect-pop"}))))))

(deftest pop-empty-idempotent-integration-test
  (testing "popping an empty inspector is idempotent"
    (is (= nil-result
           (:value (do
                     (session/message {:op "inspect-pop"})
                     (session/message {:op "inspect-pop"})))))))

(deftest push-empty-integration-test
  (testing "pushing an empty inspector index renders nil"
    (is (= nil-result
           (:value (session/message {:op "inspect-push"
                                     :idx "1"}))))))

(deftest push-empty-idempotent-integration-test
  (testing "pushing an empty inspector index is idempotent"
    (is (= nil-result
           (:value (do
                     (session/message {:op "inspect-push"
                                       :idx "1"})
                     (session/message {:op "inspect-push"
                                       :idx "1"})))))))

(deftest refresh-empty-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is (= nil-result
           (:value (session/message {:op "inspect-refresh"}))))))

(deftest refresh-empty-idempotent-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is (= nil-result
           (:value (do
                     (session/message {:op "inspect-refresh"})
                     (session/message {:op "inspect-refresh"})))))))

(deftest exception-integration-test
  (let [exception-response (session/message {:op "eval"
                                             :inspect "true"
                                             :code "(first 1)"})]

    (testing "exprs that throw exceptions return an `ex` slot"
      (is (= "class java.lang.IllegalArgumentException"
             (:ex exception-response))))

    (testing "exprs that throw exceptions return an `err` slot"
      (is (.startsWith (:err exception-response)
                       "IllegalArgumentException")))))

(deftest inspect-var-integration-test
  (testing "rendering a var"
    (is (= var-result
           (:value (session/message {:op "eval"
                                     :inspect "true"
                                     :code "#'*assert*"}))))))

(deftest inspect-expr-integration-test
  (testing "rendering an expr"
    (is (= inspect-result
           (:value (session/message {:op "eval"
                                     :inspect "true"
                                     :code code}))))))

(deftest push-integration-test
  (testing "pushing a rendered expr inspector idx"
    (is (= push-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-push"
                                       :idx "1"})))))))

(deftest pop-integration-test
  (testing "popping a rendered expr inspector"
    (is (= inspect-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-push"
                                       :idx "1"})
                     (session/message {:op "inspect-pop"})))))))

(deftest refresh-integration-test
  (testing "refreshing a rendered expr inspector"
    (is (= inspect-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-refresh"})))))))

(deftest refresh-idempotent-integration-test
  (testing "refreshing a rendered expr inspector is idempotent"
    (is (= inspect-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-refresh"})
                     (session/message {:op "inspect-refresh"})))))))

(deftest refresh-after-push-integration-test
  (testing "refreshing a rendered expr inspector after an idx is pushed"
    (is (= push-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-push"
                                       :idx "1"})
                     (session/message {:op "inspect-refresh"})))))))

(deftest session-binding-integration-test
  (testing "session bindings can be inspected"
    (is (= inspect-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code "*1"})))))))
