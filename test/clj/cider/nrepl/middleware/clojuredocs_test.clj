(ns cider.nrepl.middleware.clojuredocs-test
  (:require
   [cider.nrepl.test-session :as session]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all])
  (:import
   java.io.File))

(def ^:private test-url
  (delay
    (let [tmp (File/createTempFile "export" ".edn")
          ;; Take export.edn for testing from Orchard dependency.
          orchard-clojuredocs-export (io/resource "clojuredocs/export.edn")]
      (assert orchard-clojuredocs-export)
      (io/copy (io/reader orchard-clojuredocs-export) tmp)
      (str tmp))))

(use-fixtures :each session/session-fixture)

(deftest clojuredocs-refresh-cache-integration-test
  (testing "Invalid URL"
    (let [response (session/message {:op "cider/clojuredocs-refresh-cache"
                                     :export-edn-url "/non-existing.edn"})]
      (is (contains? (:status response) "cider/clojuredocs-refresh-cache-error"))
      (is (not (str/blank? (:err response))))))

  (testing "Valid URL"
    (let [response (session/message {:op "cider/clojuredocs-refresh-cache"
                                     :export-edn-url @test-url})]
      (is (contains? (:status response) "ok")))))

(deftest clojuredocs-lookup-integration-test
  (testing "Searching for non-existing documentation"
    (let [response (session/message {:op "cider/clojuredocs-lookup"
                                     :ns "non-existing"
                                     :sym "non-existing"})]
      (is (contains? (:status response) "no-doc"))))

  (testing "Searching for existing documentation"
    (let [response (session/message {:op "cider/clojuredocs-lookup"
                                     :ns "clojure.core"
                                     :sym "first"})
          doc (get response :clojuredocs {})]
      (is (contains? (:status response) "done"))
      (is (= "clojure.core" (:ns doc)))
      (is (= "first" (:name doc)))
      (is (every? #(contains? doc %) [:examples :see-alsos]))))

  (testing "Resolves syms in the supplied ns"
    (let [response (session/message {:op "cider/clojuredocs-lookup"
                                     :ns "cider.nrepl.middleware.clojuredocs-test"
                                     :sym "map"})
          doc (get response :clojuredocs {})]
      (is (contains? (:status response) "done"))
      (is (= "clojure.core" (:ns doc)))
      (is (= "map" (:name doc)))
      (is (every? #(contains? doc %) [:examples :see-alsos])))))

(deftest deprecated-ops-test
  (testing "Deprecated 'clojuredocs-refresh-cache' op still works"
    (let [response (session/message {:op "clojuredocs-refresh-cache"
                                     :export-edn-url @test-url})]
      (is (contains? (:status response) "ok"))))

  (testing "Deprecated 'clojuredocs-lookup' op still works"
    (let [response (session/message {:op "clojuredocs-lookup"
                                     :ns "clojure.core"
                                     :sym "first"})]
      (is (contains? (:status response) "done")))))
