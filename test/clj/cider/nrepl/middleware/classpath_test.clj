(ns cider.nrepl.middleware.classpath-test
  (:require [cider.nrepl.middleware.classpath :as cp]
            [cider.nrepl.test-session :as session]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]))

(use-fixtures :each session/session-fixture)
(deftest integration-test
  (let [response   (session/message {:op "classpath"})
        classpaths (:classpath response)]
    (is (= (:status response) #{"done"}))
    (is (> (count classpaths) 1))
    (is (every? string? classpaths))
    (is (some #(re-find #".*clojure-.*jar" %) classpaths))))

(deftest error-handling-test
  (with-redefs [cp/classpath (fn [] (throw (Exception. "cp error")))]
    (let [response   (session/message {:op "classpath"})]
      (is (= (:status response) #{"done" "classpath-error"}))
      (is (.startsWith (:err response) "java.lang.Exception: cp error"))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (:pp-stacktrace response)))))

(deftest boot-fake-classpath-test
  (let [fake-paths #{(System/getProperty "java.io.tmpdir")}
        fake-classpath (str/join (System/getProperty "path.separator") fake-paths)]
    (testing "when fake.class.path is not set"
      (is (not= fake-classpath (cp/classpath))))
    (testing "when fake.class.path is set"
      (try
        (System/setProperty "fake.class.path" fake-classpath)
        (is (= (seq fake-paths)
               (filter #(contains? fake-paths %)
                       (cp/classpath))))
        (finally
          (System/clearProperty "fake.class.path"))))))

(deftest classpath-test
  (is (set/subset? (set (-> (System/getProperty "java.class.path")
                            (str/split (re-pattern File/pathSeparator))))
                   (set (cp/classpath)))))
