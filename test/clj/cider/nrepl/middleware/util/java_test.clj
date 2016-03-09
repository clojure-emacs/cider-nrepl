(ns cider.nrepl.middleware.util.java-test
  (:require [cider.nrepl.middleware.util.java :refer :all]
            [cider.nrepl.middleware.util.misc :refer [java-api-version]]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.java.io :as io]))

(deftest test-source-info
  (let [resolve-src (comp (fnil io/resource "-none-") :file source-info)]
    (when (and jdk-sources jdk-tools)
      (testing "Source file resolution"
        (testing "from JDK"
          (is (resolve-src 'java.lang.String))
          (is (resolve-src 'java.util.regex.Matcher)))
        (testing "for non-existent classes"
          (is (not (resolve-src 'not.actually.AClass)))))
      (testing "Parse tree kinds"
        ;; classes, nested, interfaces, enums
        (is (-> (source-info 'java.util.Collection) :line)) ; interface
        (is (-> (source-info 'java.util.AbstractCollection) :line)) ; abstract class
        (is (-> (source-info 'java.lang.Thread$UncaughtExceptionHandler) :line)) ; nested interface
        (is (-> (source-info 'java.net.Authenticator$RequestorType) :line)) ; enum
        (when jdk-sources
          (is (-> (source-info 'java.sql.ClientInfoStatus) :line)))) ; top-level enum
      (testing "Source parsing"
        (is (-> (source-info 'java.util.AbstractCollection) :doc))
        (is (-> (get-in (source-info 'java.util.AbstractCollection)
                        [:members 'size])
                first val :line))))))

(deftest test-map-structure
  (when jdk-tools
    (testing "Parsed map structure = reflected map structure"
      (let [cols #{:file :line :column :doc :argnames :argtypes}
            keys= #(= (set (keys (apply dissoc %1 cols)))
                      (set (keys %2)))
            c1 (class-info* 'java.lang.String)
            c2 (with-redefs [source-info (constantly nil)]
                 (class-info* 'java.lang.String))]
        ;; Class info
        (is (keys= c1 c2))
        ;; Members
        (is (keys (:members c1)))
        (is (= (keys (:members c1))
               (keys (:members c2))))
        ;; Member info
        (is (->> (map keys=
                      (vals (:members c1))
                      (vals (:members c2)))
                 (every? true?)))))))

(deftest test-class-info
  (let [c1 (class-info 'java.lang.Thread)
        c2 (class-info 'java.lang.Thread$State)
        c3 (class-info 'not.actually.AClass)]
    (testing "Class"
      (when (and jdk-sources jdk-tools)
        (testing "source file"
          (is (string? (:file c1)))
          (is (io/resource (:file c1))))
        (testing "source file for nested class"
          (is (string? (:file c2)))
          (is (io/resource (:file c2)))))
      (testing "member info"
        (is (map? (:members c1)))
        (is (every? map? (vals (:members c1))))
        (is (apply (every-pred :name :modifiers)
                   (mapcat vals (vals (:members c1))))))
      (testing "that doesn't exist"
        (is (nil? c3))))))

(deftest test-member-info
  (let [m1 (member-info 'java.util.AbstractCollection 'size)
        m2 (member-info 'java.util.AbstractCollection 'non-existent-member)
        m3 (member-info 'not.actually.AClass 'nada)
        m4 (member-info 'java.awt.Point 'x)
        m5 (member-info 'java.lang.Class 'forName)
        m6 (member-info 'java.util.AbstractMap 'finalize)
        m7 (member-info 'java.util.HashMap 'finalize)]
    (testing "Member"
      (when (and jdk-sources jdk-tools)
        (testing "source file"
          (is (string? (:file m1)))
          (is (io/resource (:file m1))))
        (testing "line number"
          (is (number? (:line m1)))))
      (testing "arglists"
        (is (seq? (:arglists m1)))
        (is (every? vector? (:arglists m1))))
      (testing "that doesn't exist"
        (is (nil? m2)))
      (testing "in a class that doesn't exist"
        (is (nil? m3)))
      (testing "that is a field"
        (is m4))
      (testing "that is static"
        (is m5))
      (testing "implemented on immediate superclass"
        (is (not= 'java.lang.Object (:class m6))))
      (testing "implemented on ancestor superclass"
        (is (not= 'java.lang.Object (:class m7)))))))

(deftest test-arglists
  (let [+this (comp #{'this} first)]
    (testing "Arglist prepending of 'this'"
      (testing "for instance methods"
        (is (every? +this (:arglists (member-info 'java.lang.StringWriter 'write)))))
      (testing "for instance fields"
        (is (every? +this (:arglists (member-info 'java.awt.Point 'x)))))
      (testing "for static members"
        (is (not-any? +this (:arglists (member-info 'java.lang.Class 'forName)))))
      (testing "for constructors"
        (is (not-any? +this (:arglists (member-info 'java.lang.String
                                                    'java.lang.String))))))))

(deftest test-javadoc-urls
  (testing "Javadoc URL"
    (testing "for a class"
      (is (= (:javadoc (class-info 'java.lang.String))
             "java/lang/String.html")))

    (testing "for a nested class"
      (is (= (:javadoc (class-info 'java.util.AbstractMap$SimpleEntry))
             "java/util/AbstractMap.SimpleEntry.html")))

    (testing "for an interface"
      (is (= (:javadoc (class-info 'java.io.Closeable))
             "java/io/Closeable.html")))

    (let [java-version (Integer/parseInt java-api-version)]
      (if (< java-version 8)
        ;;Testing for pre-JDK 1.8 URLs
        (testing "for a member"
          (testing "with no args"
            (is (= (:javadoc (member-info 'java.util.Random 'nextLong))
                   "java/util/Random.html#nextLong()")))
          (testing "with primitive args"
            (is (= (:javadoc (member-info 'java.util.Random 'setSeed))
                   "java/util/Random.html#setSeed(long)")))
          (testing "with object args"
            (is (= (:javadoc (member-info 'java.lang.String 'contains))
                   "java/lang/String.html#contains(java.lang.CharSequence)")))
          (testing "with array args"
            (is (= (:javadoc (member-info 'java.lang.Thread 'enumerate))
                   "java/lang/Thread.html#enumerate(java.lang.Thread[])")))
          (testing "with multiple args"
            (is (= (:javadoc (member-info 'java.util.ArrayList 'subList))
                   "java/util/ArrayList.html#subList(int,%20int)")))
          (testing "with generic type erasure"
            (is (= (:javadoc (member-info 'java.util.Hashtable 'putAll))
                   "java/util/Hashtable.html#putAll(java.util.Map)"))))

        ;;Testing for post-JDK 1.8 URLs
        (testing "for a member"
          (testing "with no args"
            (is (= (:javadoc (member-info 'java.util.Random 'nextLong))
                   "java/util/Random.html#nextLong--")))
          (testing "with primitive args"
            (is (= (:javadoc (member-info 'java.util.Random 'setSeed))
                   "java/util/Random.html#setSeed-long-")))
          (testing "with object args"
            (is (= (:javadoc (member-info 'java.lang.String 'contains))
                   "java/lang/String.html#contains-java.lang.CharSequence-")))
          (testing "with array args"
            (is (= (:javadoc (member-info 'java.lang.Thread 'enumerate))
                   "java/lang/Thread.html#enumerate-java.lang.Thread:A-")))
          (testing "with multiple args"
            (is (= (:javadoc (member-info 'java.util.ArrayList 'subList))
                   "java/util/ArrayList.html#subList-int-int-")))
          (testing "with generic type erasure"
            (is (= (:javadoc (member-info 'java.util.Hashtable 'putAll))
                   "java/util/Hashtable.html#putAll-java.util.Map-"))))))))

(deftest test-class-resolution
  (let [ns (ns-name *ns*)]
    (testing "Class resolution"
      (testing "of resolvable classes"
        (is (= 'java.lang.String (:class (resolve-class ns 'String))))
        (is (= 'java.lang.String (:class (resolve-class ns 'java.lang.String)))))
      (testing "of non-resolvable 'classes'"
        (is (nil? (resolve-class ns 'NothingHere)))
        (is (nil? (resolve-class ns 'not.actually.AClass))))
      (testing "of things that aren't classes"
        (is (nil? (resolve-class ns 'assoc)))
        (is (nil? (resolve-class ns 'clojure.core)))))))

(deftest test-member-resolution
  (let [ns (ns-name *ns*)]
    (testing "Member resolution"
      (testing "of instance members"
        (is (every? #(= 'toString (:member %))
                    (resolve-member ns 'toString))))
      (testing "of non-members"
        (is (empty? (resolve-member ns 'notAMember)))))))

(deftest test-symbol-resolution
  (let [ns (ns-name *ns*)]
    (testing "Symbol resolution"
      (testing "of classes/constructors"
        (is (= 'java.lang.String (:class (resolve-symbol ns 'String)))))
      (testing "of unambiguous instance members"
        (is (= 'java.lang.SecurityManager
               (:class (resolve-symbol ns 'checkSystemClipboardAccess)))))
      (testing "of candidate instance members"
        (is (every? #(= 'toString (:member %))
                    (vals (:candidates (resolve-symbol ns 'toString))))))
      (testing "of static methods"
        (is (= 'forName (:member (resolve-symbol ns 'Class/forName)))))
      (testing "of static fields"
        (is (= 'TYPE (:member (resolve-symbol ns 'Void/TYPE)))))

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
        (is (nil? (resolve-symbol ns '.random.bunch/of$junk)))))))
