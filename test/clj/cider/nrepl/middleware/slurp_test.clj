(ns cider.nrepl.middleware.slurp-test
  (:require
   [cider.nrepl.middleware.slurp :refer [if-class slurp-url-to-content+body]]
   [clojure.java.io :as io]
   [clojure.test :as t]))

;; FIXME (arrdem 2018-04-11):
;;   Remove these if-classes when we have jdk1.8 min
(if-class java.util.Base64
  (t/deftest test-project-clj-is-clj
    (let [resp (slurp-url-to-content+body
                (.toString
                 (.toURL
                  (io/file "project.clj"))))]
      (t/is (= ["text/clojure" {}] (:content-type resp)))
      (t/is (not= "base64" (:content-transfer-encoding resp))))))

(if-class java.util.Base64
  (t/deftest test-sum-types-is-base64
    (let [resp (slurp-url-to-content+body
                (.toString
                 (io/resource "sum-types-are-cool.jpg")))]
      (t/is (= ["image/jpeg" {}] (:content-type resp)))
      (t/is (= "base64" (:content-transfer-encoding resp))))))
