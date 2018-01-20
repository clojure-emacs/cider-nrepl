(ns cider.nrepl.middleware.spec-test
  (:require [cider.nrepl.middleware.spec :as cider-spec]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))


;; integration tests

(use-fixtures :each session/session-fixture)

(deftest ^{:min-clj-version "1.9.0-alpha16"} spec-list-integration-test
  (let [filter-regex "clojure"
        filtered-spec-list (:spec-list (session/message {:op "spec-list"
                                                         :filter-regex filter-regex}))]
    (testing "Filtered spec list retrieving nothing extra"
      (is (every? #(re-find (re-pattern (str ":?" filter-regex)) %)
                  filtered-spec-list)))
    (testing "Filtering with simple words regex"
      (is (= (count filtered-spec-list)
             (count (:spec-list (session/message {:op "spec-list"
                                                  :filter-regex (str filter-regex ".+")}))))))))
