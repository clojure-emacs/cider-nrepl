(ns cider.nrepl.middleware.clojuredocs-test
  (:require
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.java.io :as io]
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
    (is+ {:status #{"cider/clojuredocs-refresh-cache-error" "done"}
          :err seq}
         (session/message {:op "cider/clojuredocs-refresh-cache"
                           :export-edn-url "/non-existing.edn"})))

  (testing "Valid URL"
    (is+ {:status #{"ok" "done"}}
         (session/message {:op "cider/clojuredocs-refresh-cache"
                           :export-edn-url @test-url}))))

(deftest clojuredocs-lookup-integration-test
  (testing "Searching for non-existing documentation"
    (is+ {:status #{"no-doc" "done"}}
         (session/message {:op "cider/clojuredocs-lookup"
                           :ns "non-existing"
                           :sym "non-existing"})))

  (testing "Searching for existing documentation"
    (is+ {:status #{"done"}
          :clojuredocs {:ns "clojure.core", :name "first",
                        :examples some?, :see-alsos some?}}
         (session/message {:op "cider/clojuredocs-lookup"
                           :ns "clojure.core"
                           :sym "first"})))

  (testing "Resolves syms in the supplied ns"
    (is+ {:status #{"done"}
          :clojuredocs {:ns "clojure.core", :name "map",
                        :examples some?, :see-alsos some?}}
         (session/message {:op "cider/clojuredocs-lookup"
                           :ns "cider.nrepl.middleware.clojuredocs-test"
                           :sym "map"}))))

(deftest deprecated-ops-test
  (testing "Deprecated 'clojuredocs-refresh-cache' op still works"
    (is+ {:status #{"ok" "done"}}
         (session/message {:op "clojuredocs-refresh-cache"
                           :export-edn-url @test-url})))

  (testing "Deprecated 'clojuredocs-lookup' op still works"
    (is+ {:status #{"done"}}
         (session/message {:op "clojuredocs-lookup"
                           :ns "clojure.core"
                           :sym "first"}))))
