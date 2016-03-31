(ns cider.nrepl.middleware.resource-test
  (:require [cider.nrepl.test-session :as session]
            [cider.nrepl.middleware.resource :as r]
            [clojure.test :refer :all]))

(use-fixtures :once session/session-fixture)
(deftest test-resource-op
  (let [response (session/message {:op "resource" :name "test.txt"})]
    (is (= #{"done"} (:status response)))
    (is (.endsWith (:resource-path response) "test/resources/test.txt"))))

(deftest test-resources-list
  (let [response (session/message {:op "resources-list"})]
    (is (= #{"done"} (:status response)))
    (is (not (empty? (:resources-list response))))
    (is (not (empty? (filter #(re-matches #"test\.txt" %) (:resources-list response)))))))
