(ns cider.nrepl.middleware.classpath-test
  (:require
   [cider.nrepl.test-session :as session]
   [cider.nrepl.middleware.classpath :refer :all]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest integration-test
  (let [response   (session/message {:op "cider/classpath"})
        classpaths (:classpath response)]
    (is (= (:status response) #{"done"}))
    (is (> (count classpaths) 1))
    (is (every? string? classpaths))
    (is (some #(re-find #".*clojure-.*jar" %) classpaths))))

(deftest error-handling-test
  (with-redefs [classpath-reply (fn [_] (throw (Exception. "cp error")))]
    (let [response (session/message {:op "cider/classpath"})]
      (is (= (:status response) #{"done" "cider/classpath-error"}))
      (is (-> response ^String (:err) (.startsWith "java.lang.Exception: cp error")))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (:pp-stacktrace response)))))

(deftest deprecated-op-test
  (testing "Deprecated 'classpath' op still works"
    (let [response (session/message {:op "classpath"})]
      (is (= (:status response) #{"done"}))
      (is (seq (:classpath response))))))

(deftest file-url?-test
  (is (file-url? (.toURL (.toURI (java.io.File. "")))))
  (is (not (file-url? (.toURL (java.net.URI. "jar:file:/tmp/test.jar!/BOOT-INF/classes"))))))
