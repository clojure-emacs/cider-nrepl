(ns cider.nrepl.middleware.resource-test
  (:require [cider.nrepl.test-session :as session]
            [cider.nrepl.middleware.resource :as r]
            [clojure.test :refer :all]))

(use-fixtures :once session/session-fixture)
(deftest resource-op-test
  (let [response (session/message {:op "resource" :name "test.txt"})]
    (is (= #{"done"} (:status response)))
    (is (.endsWith (:resource-path response) "test/resources/test.txt"))))

(deftest resources-list-test
  (testing "Basic checks"
    (let [response (session/message {:op "resources-list"})]
      (is (= #{"done"} (:status response)))
      (is (not (empty? (:resources-list response))))
      (is (not (empty? (filter #(re-matches #"test\.txt" %) (:resources-list response)))))))

  (testing "Filtering resources based on prefixes"
    (let [response (session/message {:op "resources-list" :prefix "see-also"})]
      (is (= #{"done"} (:status response)))
      (is (not (empty? (:resources-list response))))
      (is (every? #(.startsWith % "see-also") (:resources-list response)))
      (is (contains? (set (:resources-list response)) "see-also.edn")))))

(deftest resource-op-error-handling-test
  (with-redefs [r/resource-path (fn [& _] (throw (Exception. "resource")))]
    (let [response (session/message {:op "resource" :name "test.txt"})]
      (is (= "class java.lang.Exception" (:ex response)))
      (is (= #{"done" "resource-error"} (:status response)))
      (is (:pp-stacktrace response)))))

(deftest resources-list-op-error-handling-test
  (with-redefs [r/resources-list (fn [& _] (throw (Exception. "resources list")))]
    (let [response (session/message {:op "resources-list"})]
      (is (= "class java.lang.Exception" (:ex response)))
      (is (= #{"done" "resources-list-error"} (:status response)))
      (is (:pp-stacktrace response)))))
