(ns cider.nrepl.middleware.util.java-test
  (:require [cider.nrepl.middleware.util.java :refer :all]
            [clojure.test :refer :all]
            [clojure.java.io :as io]))

(deftest test-sources
  (let [resolve-src (comp io/resource java-source)]
    (testing "Source file resolution"
      (testing "from Clojure"
        (is (resolve-src "clojure.lang.Compiler"))
        (is (resolve-src "clojure.lang.PersistentHashSet")))
      (testing "from JDK"
        (when jdk-sources ; system dependent; not managed by project.clj
          (is (resolve-src "java.lang.String"))
          (is (resolve-src "java.util.regex.Matcher"))))
      (testing "for non-existent classes"
        (is (not (resolve-src "not.actually.AClass")))))))

(deftest test-class-info
  (let [c1 (class-info "clojure.lang.PersistentHashMap")
        c2 (class-info "clojure.lang.PersistentHashMap$ArrayNode")
        c3 (class-info "not.actually.AClass")]
    (testing "Class"
      (testing "source file"
        (is (string? (:file c1)))
        (is (io/resource (:file c1))))
      (testing "source file for nested class"
        (is (string? (:file c2)))
        (is (io/resource (:file c2))))
      (testing "method info"
        (is (map? (:methods c1)))
        (is (every? map? (vals (:methods c1))))
        (is (apply (every-pred :ret :args :line)
                   (mapcat vals (vals (:methods c1))))))
      (testing "that doesn't exist"
        (is (nil? c3))))))

(deftest test-method-info
  (let [m1 (method-info "clojure.lang.PersistentHashMap" "assoc")
        m2 (method-info "clojure.lang.PersistentHashMap" "nothing")
        m3 (method-info "not.actually.AClass" "nada")]
    (testing "Method"
      (testing "source file"
        (is (string? (:file m1)))
        (is (io/resource (:file m1))))
      (testing "line number"
        (is (number? (:line m1))))
      (testing "arglists"
        (is (seq? (:arglists m1)))
        (is (every? vector? (:arglists m1))))
      (testing "that doesn't exist"
        (is (nil? m2)))
      (testing "in a class that doesn't exist"
        (is (nil? m3))))))
