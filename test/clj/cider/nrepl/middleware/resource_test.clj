(ns cider.nrepl.middleware.resource-test
  (:require
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :once session/session-fixture)
(deftest resource-op-test
  (let [response (session/message {:op "cider/resource" :name "test.txt"})]
    (is (= #{"done"} (:status response)))
    (is (-> response ^String (:resource-path) (.endsWith "test/resources/test.txt")))))

(deftest resources-list-test
  (testing "Basic checks"
    (let [response (session/message {:op "cider/resources-list"})]
      (is (= #{"done"} (:status response)))
      (is (seq (:resources-list response)))
      (is (seq (filter #(re-matches #"test\.txt" (:relpath %))
                       (:resources-list response))))
      (is (seq (filter #(re-matches #".*test/resources/test\.txt" (:file %))
                       (:resources-list response)))))))

(deftest deprecated-ops-test
  (testing "Deprecated 'resource' op still works"
    (let [response (session/message {:op "resource" :name "test.txt"})]
      (is (= #{"done"} (:status response)))
      (is (-> response ^String (:resource-path) (.endsWith "test/resources/test.txt")))))

  (testing "Deprecated 'resources-list' op still works"
    (let [response (session/message {:op "resources-list"})]
      (is (= #{"done"} (:status response)))
      (is (seq (:resources-list response))))))
