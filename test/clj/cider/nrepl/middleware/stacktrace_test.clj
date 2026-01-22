(ns cider.nrepl.middleware.stacktrace-test
  (:require
   [cider.nrepl.middleware.stacktrace :as sut :refer :all]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

;; Stacktrace op (deprecated)

(deftest stacktrace-bound-test
  (testing "stacktrace op with most recent exception bound"
    (session/message {:op "eval" :code "(first 1)"})
    (let [response (session/message {:op "cider/stacktrace"})]
      (testing "returns the exception class"
        (is (= "java.lang.IllegalArgumentException" (:class response))))
      (testing "returns the exception message"
        (is (= "Don't know how to create ISeq from: java.lang.Long" (:message response))))
      (testing "returns done status"
        (is (= #{"done"} (:status response)))))))

(deftest stacktrace-unbound-test
  (testing "stacktrace op with most recent exception unbound"
    (let [response (session/message {:op "cider/stacktrace"})]
      (testing "returns done and no-error status"
        (is (= #{"done" "no-error"} (:status response)))))))

;; Analyze last stacktrace op

(deftest analyze-last-stacktrace-bound-test
  (testing "stacktrace op with most recent exception bound"
    (session/message {:op "eval" :code "(first 1)"})
    (let [response (session/message {:op "cider/analyze-last-stacktrace"})]
      (testing "returns the exception class"
        (is (= "java.lang.IllegalArgumentException" (:class response))))
      (testing "returns the exception message"
        (is (= "Don't know how to create ISeq from: java.lang.Long" (:message response))))
      (testing "returns done status"
        (is (= #{"done"} (:status response)))))

    (testing "`inspect-last-exception` op"
      (testing "Index 0 for an exception without extra causes"
        (let [{[^String first-value] :value} (session/message  {:op "cider/inspect-last-exception" :index 0})]
          (is (.startsWith first-value "(\"Class: \" (:value")
              "Returns an Inspector response")))
      (testing "Index out of bounds"
        (is (nil? (:value (session/message {:op "cider/inspect-last-exception" :index 1})))))
      (testing "Indices 0, 1, 2 for an exception with one extra cause"
        (session/message {:op "eval" :code "(deref (future (/ 2 0)))"})
        (session/message {:op "cider/analyze-last-stacktrace"})
        (is (seq (:value (session/message  {:op "cider/inspect-last-exception" :index 0}))))
        (let [{[^String first-value] :value} (session/message  {:op "cider/inspect-last-exception" :index 0})]
          (is (.startsWith first-value "(\"Class: \" (:value \"java.util.concurrent.ExecutionException")
              "Returns an Inspector response for index 0"))
        (let [{[^String first-value] :value} (session/message  {:op "cider/inspect-last-exception" :index 1})]
          (is (.startsWith first-value "(\"Class: \" (:value \"java.lang.ArithmeticException")
              "Returns a different Inspector response for index 1"))
        (is (nil? (:value (session/message {:op "cider/inspect-last-exception" :index 2})))
            "Doesn't return a value past the last index")))))

(deftest analyze-last-stacktrace-unbound-test
  (testing "stacktrace op with most recent exception unbound"
    (let [response (session/message {:op "cider/analyze-last-stacktrace"})]
      (testing "returns done and no-error status"
        (is (= #{"done" "no-error"} (:status response)))))))

(deftest deprecated-ops-test
  (testing "Deprecated 'stacktrace' op still works"
    (session/message {:op "eval" :code "(first 1)"})
    (let [response (session/message {:op "stacktrace"})]
      (is (= "java.lang.IllegalArgumentException" (:class response)))
      (is (= #{"done"} (:status response)))))

  (testing "Deprecated 'analyze-last-stacktrace' op still works"
    (session/message {:op "eval" :code "(first 1)"})
    (let [response (session/message {:op "analyze-last-stacktrace"})]
      (is (= "java.lang.IllegalArgumentException" (:class response)))
      (is (= #{"done"} (:status response))))))
