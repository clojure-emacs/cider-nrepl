(ns cider.nrepl.middleware.stacktrace-test
  (:require
   [cider.nrepl.middleware.stacktrace :as sut :refer :all]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest stacktrace-most-recent-bound-test
  (testing "stacktrace op with most recent exception bound"
    (session/message {:op "eval" :code "(first 1)"})
    (let [response (session/message {:op "stacktrace"})]
      (testing "returns the exception class"
        (is (= "java.lang.IllegalArgumentException" (:class response))))
      (testing "returns the exception message"
        (is (= "Don't know how to create ISeq from: java.lang.Long" (:message response))))
      (testing "returns done status"
        (is (= #{"done"} (:status response)))))))

(deftest stacktrace-most-recent-unbound-test
  (testing "stacktrace op with most recent exception unbound"
    (let [response (session/message {:op "stacktrace"})]
      (testing "returns done and no-error status"
        (is (= #{"done" "no-error"} (:status response)))))))

(deftest parse-stacktrace-test
  (testing "stacktrace op with stacktrace parameter"
    (let [response (session/message {:op "parse-stacktrace" "stacktrace" (pr-str (ex-info "BOOM" {:boom :data}))})]
      (testing "returns the exception class"
        (is (= "clojure.lang.ExceptionInfo" (:class response))))
      (testing "returns the exception message"
        (is (= "BOOM" (:message response))))
      (testing "returns done status"
        (is (= #{"done"} (:status response)))))))

(deftest parse-stacktrace-invalid-test
  (testing "stacktrace op with invalid stacktrace parameter"
    (let [response (session/message {:op "parse-stacktrace" "stacktrace" "invalid"})]
      (testing "returns done and no-error status"
        (is (= #{"done" "no-error"} (:status response)))))))
