(ns classpath-test
  (:require
   [clojure.java.classpath]
   [clojure.test :refer :all]))

(deftest works
  (testing "The presence of the cider-nrepl library does not affect the clojure.java.classpath library, particularly on JDK11"
    (is (seq (clojure.java.classpath/classpath-directories)))))
