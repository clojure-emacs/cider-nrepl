(ns cider.nrepl.middleware.slurp-test
  (:require
   [cider.nrepl.middleware.slurp :refer [slurp-url-to-content+body]]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [clojure.string :as str]
   [orchard.misc :refer [java-api-version]]))

(t/deftest test-project-clj-is-clj
  (let [resp (-> "project.clj"
                 io/file
                 io/as-url
                 .toString
                 slurp-url-to-content+body)]
    (t/is (= ["text/clojure" {}] (:content-type resp)))
    (t/is (not= "base64" (:content-transfer-encoding resp)))))

(when-not (= 8 java-api-version)
  (t/deftest test-sum-types-is-base64
    (let [resp (slurp-url-to-content+body
                (.toString
                 (io/resource "sum-types-are-cool.jpg")))]
      (t/is (= ["image/jpeg" {}] (:content-type resp)))
      (t/is (= "base64" (:content-transfer-encoding resp))))))

(t/deftest test-unrecognized-file
  (let [resp (slurp-url-to-content+body
              (.toString (io/resource "unknown.unknown")))]
    (t/is (= ["application/octet-stream" {}] (:content-type resp)))
    (t/is (str/starts-with? (:body resp) "#binary[location="))
    (t/is (str/ends-with? (:body resp) ",size=681]"))))

(t/deftest test-directory
  (let [resp (-> "test"
                 io/file
                 io/as-url
                 .toString
                 slurp-url-to-content+body)]
    (t/is (= ["application/octet-stream" {}] (:content-type resp)))
    (t/is (str/starts-with? (:body resp) "#binary[location="))
    (t/is (str/ends-with? (:body resp) ",size=0]"))))
