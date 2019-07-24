(ns cider.nrepl.middleware.clojuredocs-test
  (:require
   [cider.nrepl.test-session :as session]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(def ^:private test-url "test/resources/cider/nrepl/clojuredocs/export.edn")

(use-fixtures :each session/session-fixture)

(deftest clojuredocs-refresh-cache-integration-test
  (testing "Invalid URL"
    (let [response (session/message {:op "clojuredocs-refresh-cache"
                                     :export-edn-url "/non-existing.edn"})]
      (is (contains? (:status response) "clojuredocs-refresh-cache-error"))
      (is (not (str/blank? (:err response))))))

  (testing "Valid URL"
    (let [response (session/message {:op "clojuredocs-refresh-cache"
                                     :export-edn-url test-url})]
      (is (contains? (:status response) "ok")))))

(deftest clojuredocs-lookup-integration-test
  (testing "Find not existing document"
    (let [response (session/message {:op "clojuredocs-lookup"
                                     :ns "non-existing"
                                     :symbol "non-existing"
                                     :export-edn-url test-url})]
      (is (contains? (:status response) "no-document"))))

  (testing "Find existing document"
    (let [response (session/message {:op "clojuredocs-lookup"
                                     :ns "clojure.core"
                                     :symbol "first"
                                     :export-edn-url test-url})
          doc (get response :clojuredocs {})]
      (is (contains? (:status response) "done"))
      (is (= "clojure.core" (:ns doc)))
      (is (= "first" (:name doc)))
      (is (every? #(contains? doc %) [:examples :see-alsos])))))
