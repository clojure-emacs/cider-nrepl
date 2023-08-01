(ns cider.nrepl.middleware.slurp-test
  (:require
   [cider.nrepl.middleware.slurp :as sut]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [clojure.string :as str]))

(t/deftest test-project-clj-is-clj
  (let [resp (-> "project.clj"
                 io/file
                 io/as-url
                 .toString
                 sut/slurp-url-to-content+body)]
    (t/is (= ["text/clojure" {}] (:content-type resp)))
    (t/is (not= "base64" (:content-transfer-encoding resp)))))

(t/deftest test-sum-types-is-base64
  (let [resp (sut/slurp-url-to-content+body
              (.toString
               (io/resource "sum-types-are-cool.jpg")))]
    (t/is (= ["image/jpeg" {}] (:content-type resp)))
    (t/is (= "base64" (:content-transfer-encoding resp)))))

(t/deftest test-unrecognized-file
  (let [resp (sut/slurp-url-to-content+body
              (.toString (io/resource "unknown.unknown")))]
    (t/is (= ["application/octet-stream" {}] (:content-type resp)))
    (t/is (str/starts-with? (:body resp) "#binary[location="))
    (t/is (str/ends-with? (:body resp) ",size=681]"))))

(t/deftest test-directory
  (let [resp (-> "test"
                 io/file
                 io/as-url
                 .toString
                 sut/slurp-url-to-content+body)]
    (t/is (= ["application/octet-stream" {}] (:content-type resp)))
    (t/is (str/starts-with? (:body resp) "#binary[location="))
    (t/is (str/ends-with? (:body resp) ",size=0]"))))

(t/deftest test-url-content-type
  (t/testing "When a url gives content type as nil, then default content-type should be taken"
    (with-redefs [sut/get-content-type (constantly nil)]
      (let [resp (sut/slurp-url-to-content+body "https://clojars.org/no-such-page")]
        (t/is (= ["text/html" {}] (:content-type resp)))
        (t/is (= "" (:body resp)))))))
