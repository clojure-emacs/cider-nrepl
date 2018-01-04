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

(deftest normalize-spec-form-test
  (testing "All fn* subforms should be normalized"
    (is (= (cider-spec/normalize-spec-form '(clojure.spec.alpha/fspec
                                             :args (clojure.spec.alpha/and
                                                    (fn* [p1__22097#]
                                                         (clojure.core/< (:start p1__22097#) (:end p1__22097#)))
                                                    (fn [%]
                                                      (clojure.core/< (:start %) (:end %))))
                                             :ret (fn* [p2__33098#]
                                                       (clojure.core/> (:start p2__33098#) (:end p2__33098#)))
                                             :fn nil))
           '(clojure.spec.alpha/fspec
             :args (clojure.spec.alpha/and
                    (fn [%] (clojure.core/< (:start %) (:end %)))
                    (fn [%] (clojure.core/< (:start %) (:end %))))
             :ret (fn [%] (clojure.core/> (:start %) (:end %)))
             :fn nil)))))
