(ns cider.nrepl.middleware.classpath-test
  (:require [cider.nrepl.test-session :as session]
            [cider.nrepl.middleware.classpath :as cp]
            [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)
(deftest integration-test
  (let [response   (session/message {:op "classpath"})
        classpaths (:classpath response)]
    (is (= (:status response) #{"done"}))
    (is (> (count classpaths) 1))
    (is (every? string? classpaths))
    (is (some #(re-find #".*clojure-.*jar" %) classpaths))))

(deftest error-handling
  (with-redefs [cp/classpath (fn [] (throw (Exception. "cp error")))]
    (let [response   (session/message {:op "classpath"})]
      (is (= (:status response) #{"done" "classpath-error"}))
      (is (.startsWith (:err response) "java.lang.Exception: cp error"))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (:pp-stacktrace response)))))
