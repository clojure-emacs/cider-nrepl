(ns cider.nrepl.middleware.util.java
  "Source info for Java classes and members"
  {:author "Jeff Valk"}
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dynapath.util :as dp])
  (:import (clojure.asm ClassReader ClassVisitor MethodVisitor Opcodes)
           (clojure.asm.commons Method)))

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

(defn java-doc
  "Return the relative .html javadoc path for the class name."
  [class]
  (-> (str/replace class "." "/")
      (str/replace "$" ".")
      (str ".html")))


;;; ## Class/Method Info

;; Getting class member info (i.e. method names, argument/return types, etc) is
;; straightforward using reflection...but this leaves us without source
;; location. For line numbers, we either have to parse bytecode, or the .java
;; source itself. For present purposes, we'll take the former approach.

;; N.b. Java class LineNumberTables map source lines to bytecode instructions.
;; This means that the line numbers returned for methods are generally the first
;; executable line of the method, rather than its declaration. (Constructors are
;; an exception to this.)

;; ASM Workaround: Clojure <=1.5 bundles ASM 3.3; Clojure 1.6 upgraded to ASM
;; 4.1, which had breaking API changes. Declaring our own ASM dependency causes
;; headaches (see issue #44), so we'll work around the API changes.
(def asm
  "The ASM API version"
  (try (import (clojure.asm.commons EmptyVisitor)) 3
       (catch Exception _ 4)))

;; In the ASM4 API, visitors are abstract classes with stub methods whose
;; constructrors require the API version as an argument. In ASM3, visitor were
;; interfaces, and stubs were provided by the `EmptyVisitor` class, which has
;; only a nullary constructor.
(defmacro proxy-asm
  "Like `proxy`, but provides a shim to support both ASM3 and ASM4 visitors."
  [supers args & fs]
  (let [[supers args] (if (< asm 4)
                        [`[EmptyVisitor] []]
                        [supers `[Opcodes/ASM4]])]
    `(proxy ~supers ~args ~@fs)))

(defn class-info
  "For the named class, return Java source and member info, including line
  numbers. Methods are indexed first by name, and then by argument types to
  list all overloads. Parent (super) class info is appended recursively."
  [class]
  (let [methods (atom {})
        parent  (atom nil)
        typesym #(-> % .getClassName symbol)
        visitor (proxy-asm [ClassVisitor] []
                  (visit [version access name signature super interfaces]
                    (when super
                      (let [super (str/replace super "/" ".")]
                        (reset! parent (class-info super)))))
                  (visitMethod [access name desc signature exceptions]
                    (let [m (Method. name desc)
                          ret (typesym (.getReturnType m))
                          args (mapv typesym (.getArgumentTypes m))]
                      (proxy-asm [MethodVisitor] []
                        (visitLineNumber [line label]
                          (when-not (get-in @methods [name args])
                            (swap! methods assoc-in [name args]
                                   {:ret ret :args args :line line})))))))]
    (try (-> (ClassReader. class)
             (.accept visitor 0))
         {:class class
          :super @parent
          :methods @methods
          :file (java-source class)
          :javadoc (java-doc class)}
         (catch Exception _))))

;; N.b. Where a method's bytecode signature differs from its declared signature
;; (other than collection generics), the javadoc method URL can't be inferred as
;; done below. (Specifically, this applies to varargs and non-collection
;; generics, e.g. `java/util/Arrays.html#asList(T...)`.) Since the method is
;; just a URL fragment, the javadoc link will simply navigate to the parent
;; class in these cases.
(defn method-info
  "Return Java member info, including argument (type) lists, javadoc, and source
  file/line. If the member is an overloaded method, line number and javadoc
  signature are that of the first overload. If the method's implementation is in
  a superclass, source file/line and javadoc are that of the implemention."
  [class method]
  (let [c (->> (class-info class)
               (iterate :super)
               (take-while identity)
               (filter #(get-in % [:methods method]))
               (first))]
    (when-let [m (get-in c [:methods method])]
      (let [m* (first (sort-by :line (vals m)))]
        (-> (dissoc c :methods :super)
            (assoc :class class
                   :method method
                   :line (:line m*)
                   :arglists (keys m)
                   :javadoc (format "%s#%s(%s)" (:javadoc c) method
                                    (str/join ", " (:args m*)))
                   :doc nil))))))

(defn constructor-info
  "Return contructor method info for the named class."
  [class]
  (method-info class "<init>"))


;;; ## Class/Method Resolution
;; A namespace provides a search context for resolving a symbol to a Java class
;; or member. Classes, constructors, and static members can be resolved
;; unambiguously. With instance methods, more than one imported class may have
;; a member that matches the searched symbol. In such cases, the result will be
;; a list of resolved candidate methods. (Note that this list could be narrowed
;; by considering arity, and narrowed further *if* we could consider argument
;; types...)

(defn resolve-class
  "Given a namespace name and class, search the imported classes and return
  class info."
  [ns sym]
  (when-let [ns (find-ns (symbol ns))]
    (let [c (try (ns-resolve ns (symbol sym))
                 (catch Exception _))]
      (when (class? c)
        (class-info (.getName c))))))

(defn resolve-method
  "Given a namespace name and method, search the imported classes and return
  a list of each matching method's info."
  [ns sym]
  (when-let [ns (find-ns (symbol ns))]
    (->> (vals (ns-imports ns))
         (map #(method-info (.getName %) (str sym)))
         (filter identity)
         (distinct))))

(defn resolve-symbol
  "Given a namespace name and a class or method, resolve the class/method from
  the imported classes. For a class symbol, constructor info is returned. Class,
  constructor, and static calls are resolved unambiguously. Instance methods are
  resolved unambiguously if defined by only one imported class. If multiple
  imported classes have a method by that name, a map of class names to method
  info is returned as `:candidates`."
  [ns sym]
  (let [sym (str/replace sym #"^\.|\.$" "") ; strip leading/trailing dot
        [class static-member] (str/split sym #"/" 2)]
    (if-let [c (resolve-class ns class)]
      (if static-member
        (method-info (:class c) static-member)
        (constructor-info (:class c)))
      (when-let [ms (seq (resolve-method ns sym))]
        (if (= 1 (count ms))
          (first ms)
          {:candidates (zipmap (map :class ms) ms)})))))
