(ns cider.nrepl.middleware.util.java
  "Source info for Java classes and members"
  {:author "Jeff Valk"}
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dynapath.util :as dp])
  (:import (org.objectweb.asm ClassReader ClassVisitor MethodVisitor Opcodes)
           (org.objectweb.asm.commons Method)))

;;; ## Source Files
;; Java source files are resolved from the classpath. For library dependencies,
;; this simply entails having the corresponding source artifacts in the
;; project's dev dependencies. The core Java API classes are the exception to
;; this, since these are external to lein/maven dependency management. For
;; these, we look at the JDK itself and add the source classpath entry manually.

;; This is used only to add text resources to the classpath. Issues of class
;; dependency are not in play.
(defn add-classpath!
  "Similar to the deprecated `clojure.core/add-classpath`, adds the URL to the
  classpath and returns it if successful, or nil otherwise."
  [url]
  (let [classloader (->> (.. Thread currentThread getContextClassLoader)
                         (iterate #(.getParent %))
                         (take-while identity)
                         (filter dp/addable-classpath?)
                         (last))]
    (when (dp/add-classpath-url classloader url)
      url)))

(def jdk-sources
  "The JDK sources path. If available, this is added to the classpath. By
  convention, this is the file `src.zip` in the root of the JDK directory
  (parent of the `java.home` JRE directory)."
  (let [zip (-> (io/file (System/getProperty "java.home"))
                (.getParentFile)
                (io/file "src.zip"))]
    (when (.canRead zip)
      (add-classpath! (io/as-url zip)))))

(defn java-source
  "Return the relative .java source path for the top-level class name."
  [class]
  (-> (str/replace class #"\$.*" "")
      (str/replace "." "/")
      (str ".java")))


;;; ## Class/Method Info

;; Getting class member info (i.e. method names, argument/return types, etc) is
;; straightforward using reflection...but this leaves us without source
;; location. For line numbers, we either have to parse bytecode, or the .java
;; source itself. For present purposes, we'll take the former approach.

;; N.b. Java class LineNumberTables map source lines to bytecode instructions.
;; This means that the line numbers returned for methods are generally the first
;; executable line of the method, rather than its declaration. (Constructors are
;; an exception to this.)

(defn class-info
  "For the named class, return Java source and member info, including line
  numbers. Methods are indexed first by name, and then by argument types to
  list all overloads."
  [class]
  (let [methods (atom {})
        typesym #(-> % .getClassName symbol)
        visitor (proxy [ClassVisitor] [Opcodes/ASM4]
                  (visitMethod [access name desc signature exceptions]
                    (let [m (Method. name desc)
                          ret (typesym (.getReturnType m))
                          args (mapv typesym (.getArgumentTypes m))]
                      (proxy [MethodVisitor] [Opcodes/ASM4]
                        (visitLineNumber [line label]
                          (when-not (get-in @methods [name args])
                            (swap! methods assoc-in [name args]
                                   {:ret ret :args args :line line})))))))]
    (try (-> (ClassReader. class)
             (.accept visitor 0))
         {:class class
          :methods @methods
          :file (java-source class)}
         (catch Exception _))))

(defn method-info
  "Return Java member info, including argument (type) lists and source
  file/line. If the member is an overloaded method, line number is that of the
  first overload."
  [class method]
  (let [c (class-info class)]
    (when-let [m (get-in c [:methods method])]
      (-> (dissoc c :methods)
          (assoc :method method
                 :line (->> (vals m) (map :line) sort first)
                 :arglists (keys m)
                 :doc nil)))))
