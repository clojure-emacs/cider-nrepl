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
        m3 (method-info "not.actually.AClass" "nada")
        m4 (method-info "java.util.AbstractMap" "finalize")
        m5 (method-info "java.util.HashMap" "finalize")
        m6 (method-info "java.lang.Class" "forName")]
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
        (is (nil? m3)))
      (testing "implemented on immediate superclass"
        (is (not= "java.lang.Object" (:class m4)))
        (is (= "java/lang/Object.java" (:file m4))))
      (testing "implemented on ancestor superclass"
        (is (not= "java.lang.Object" (:class m5)))
        (is (= "java/lang/Object.java" (:file m5))))
      (testing "that is static"
        (is m6)))))

(deftest test-class-resolution
  (let [ns (ns-name *ns*)]
    (testing "Class resolution"
      (testing "of resolvable classes"
        (is (= "java.lang.String" (:class (resolve-class ns 'String))))
        (is (= "java.lang.String" (:class (resolve-class ns 'java.lang.String)))))
      (testing "of non-resolvable 'classes'"
        (is (nil? (resolve-class ns 'NothingHere)))
        (is (nil? (resolve-class ns 'not.actually.AClass))))
      (testing "of things that aren't classes"
        (is (nil? (resolve-class ns 'assoc)))
        (is (nil? (resolve-class ns 'clojure.core)))))))

(deftest test-method-resolution
  (let [ns (ns-name *ns*)]
    (testing "Method resolution"
      (testing "of instance methods"
        (is (every? #(= "toString" (:method %))
                    (resolve-method ns 'toString))))
      (testing "of non-methods"
        (is (empty? (resolve-method ns 'notAMethod)))))))

(deftest test-symbol-resolution
  (let [ns (ns-name *ns*)]
    (testing "Symbol resolution"
      (testing "of classes/constructors"
        (is (= "<init>" (:method (resolve-symbol ns 'String)))))
      (testing "of unambiguous instance methods"
        (is (= "java.lang.SecurityManager"
               (:class (resolve-symbol ns 'checkSystemClipboardAccess)))))
      (testing "of candidate instance methods"
        (is (every? #(= "toString" (:method %))
                    (vals (:candidates (resolve-symbol ns 'toString))))))
      (testing "of static methods"
        (is (= "forName" (:method (resolve-symbol ns 'Class/forName)))))

      (testing "equality of qualified vs unqualified"
        (testing "classes"
          (is (= (resolve-symbol ns 'java.lang.String)
                 (resolve-symbol ns 'String))))
        (testing "constructors"
          (is (= (resolve-symbol ns 'java.lang.Exception.)
                 (resolve-symbol ns 'Exception.))))
        (testing "static methods"
          (is (= (resolve-symbol ns 'java.lang.Class/forName)
                 (resolve-symbol ns 'Class/forName))))
        (testing "static fields"
          (is (= (resolve-symbol ns 'java.lang.Void/TYPE)
                 (resolve-symbol ns 'Void/TYPE)))))

      (testing "equality of dotted"
        (testing "constructor syntax"
          (is (= (resolve-symbol ns 'Exception)
                 (resolve-symbol ns 'Exception.))))
        (testing "method syntax"
          (is (= (resolve-symbol ns 'toString)
                 (resolve-symbol ns '.toString)))))

      (testing "of things that shouldn't resolve"
        (is (nil? (resolve-symbol ns 'MissingUnqualifiedClass)))
        (is (nil? (resolve-symbol ns 'missing.qualified.Class)))
        (is (nil? (resolve-symbol ns 'MissingUnqualifiedCtor.)))
        (is (nil? (resolve-symbol ns 'missing.qualified.Ctor.)))
        (is (nil? (resolve-symbol ns 'MissingUnqualified/staticMethod)))
        (is (nil? (resolve-symbol ns 'missing.Qualified/staticMethod)))
        (is (nil? (resolve-symbol ns 'missingMethod)))
        (is (nil? (resolve-symbol ns '.missingDottedMethod)))
        (is (nil? (resolve-symbol ns '.random.bunch/of$junk)))
        (is (nil? (resolve-symbol ns :wrong-type)))
        (is (nil? (resolve-symbol ns 12345)))
        (is (nil? (resolve-symbol ns true)))))))
