(ns cider.nrepl.middleware.stacktrace-test
  (:require
   [cider.nrepl.middleware.stacktrace :as sut :refer :all]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest stacktrace-no-exception-test
  (testing "stacktrace op without any exception bound to *e)"
    (let [response (session/message {:op "stacktrace"})]
      (testing "status field"
        (is (= #{"no-error" "done"} (:status response)))))))

(deftest stacktrace-exception-test
  (testing "stacktrace op with an exception bound to *e)"
    (session/message {:op "eval" :code    "(first 1)"})
    (let [response (session/message {:op "stacktrace"})]
      (testing "class field"
        (is (= "java.lang.IllegalArgumentException" (:class response))))
      (testing "message field"
        (is (= "Don't know how to create ISeq from: java.lang.Long" (:message response))))
      (testing "status field"
        (is (= #{"done"} (:status response)))))))
