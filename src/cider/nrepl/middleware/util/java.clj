(ns cider.nrepl.middleware.util.java
  "Info for Java classes and members"
  {:author "Jeff Valk"}
  (:require [cider.nrepl.middleware.util.misc :as util]
            [clojure.java.io :as io]
            [clojure.reflect :as r]
            [clojure.string :as str]
            [dynapath.util :as dp])
  (:import (clojure.lang IPersistentMap)
           (clojure.reflect Constructor Field JavaReflector Method)))

;;; ## Java Class/Member Info
;; Getting class and member info (i.e. type hierarchy, method names,
;; argument/return types, etc) is straightforward using reflection; this
;; provides the basis of Java metadata support. When the source is available
;; (and `tools.jar` is too), we supplement reflection with source analysis to
;; get file, line, and column info, as well as docstrings and argument names.

;;; ## Classpath
;; Java source files are resolved from the classpath. For library dependencies,
;; this simply entails having the corresponding source artifacts in the
;; project's dev dependencies. The core Java API classes are the exception to
;; this, since these are external to lein/maven dependency management. For
;; these, we look at the JDK itself and add the source classpath entry manually.
;; Parsing these also requires having `tools.jar` on the classpath, which we'll
;; have to add as well.

;; This is used only to add JDK resources to the classpath. Issues of class
;; dependency are not in play.
(defn add-classpath!
  "Similar to the deprecated `clojure.core/add-classpath`, adds the URL to the
  classpath and returns it if successful, or nil otherwise."
  [url]
  (let [classloader (->> (.. Thread currentThread getContextClassLoader)
                         (iterate #(.getParent ^ClassLoader %))
                         (take-while identity)
                         (filter dp/addable-classpath?)
                         (last))]
    (when (dp/add-classpath-url classloader url)
      url)))

(def jdk-root
  "The JDK root directory (parent of the `java.home` JRE directory)"
  (-> (io/file (System/getProperty "java.home"))
      (.getParentFile)))

(def jdk-sources
  "The JDK sources path. If available, this is added to the classpath. By
  convention, this is the file `src.zip` in the root of the JDK directory."
  (let [zip (io/file jdk-root "src.zip")]
    (when (.canRead zip)
      (add-classpath! (io/as-url zip)))))

(def jdk-tools
  "The JDK `tools.jar` path. If available, this is added to the classpath."
  (let [jar (io/file jdk-root "lib" "tools.jar")]
    (when (.canRead jar)
      (add-classpath! (io/as-url jar)))))


;;; ## Javadoc URLs
;; Relative Javadoc URLs can be constructed from class/member signatures.
;;
;; N.b. Where a method's bytecode signature differs from its declared signature
;; (other than collection generics), the javadoc method URL can't be inferred as
;; done below. (Specifically, this applies to varargs and non-collection
;; generics, e.g. `java/util/Arrays.html#asList(T...)`.) Since the member is
;; just a URL fragment, the javadoc link will simply navigate to the parent
;; class in these cases.
(defn javadoc-url
  "Return the relative `.html` javadoc path and member fragment."
  ([class]
     (-> (str/replace (str class) "." "/")
         (str/replace "$" ".")
         (str ".html")))
  ([class member argtypes]
     (str (javadoc-url class) "#" member
          (when argtypes
            (str "(" (str/join ",%20" argtypes) ")")))))


;;; ## Class Metadata Assembly
;; We construct metadata at the class level, first using `reflect-info` to
;; transform the metadata returned by `clojure.reflect/reflect`. This is then
;; merged with a source analysis pass (when available) from `source-info`. The
;; nested map structure and keys returned by these two functions is identical
;; for the same class. Class members are indexed first by name, then argument
;; types.

(defn typesym
  [o]
  (when o (symbol (r/typename o))))

(defprotocol Reflected
  (reflect-info [o]))

(extend-protocol Reflected
  Constructor
  (reflect-info [c]
    {:argtypes (mapv typesym (:parameter-types c))
     :throws (map typesym (:exception-types c))})

  Method
  (reflect-info [m]
    {:argtypes (mapv typesym (:parameter-types m))
     :throws (map typesym (:exception-types m))
     :returns (typesym (:return-type m))})

  Field
  (reflect-info [f]
    {:type (typesym (:type f))})

  IPersistentMap ; => Class
  (reflect-info [c]
    {:name (:name c)
     :modifiers (:flags c)
     :members (->> (:members c)
                   ;; Merge type-specific attributes with common ones.
                   (map (fn [m]
                          (merge {:name (:name m)
                                  :modifiers (:flags m)}
                                 (reflect-info m))))
                   ;; Index by name, argtypes. Args for fields are nil.
                   (group-by :name)
                   (reduce (fn [ret [n ms]]
                             (assoc ret n (zipmap (map :argtypes ms) ms)))
                           {}))}))

(def source-info
  "When `tools.jar` is available, return class info from its parsed source;
  otherwise return nil."
  (if jdk-tools
    (do (require '[cider.nrepl.middleware.util.java.parser :as src])
        (resolve 'src/source-info))
    (constantly nil)))

(defn class-info*
  "For the class symbol, return Java class and member info. Members are indexed
  first by name, and then by argument types to list all overloads."
  [class]
  (when-let [^Class c (try (Class/forName (str class))
                           (catch Exception _))]
    (let [r (JavaReflector. (.getClassLoader c))] ; for dynamically loaded classes
      (util/deep-merge (reflect-info (r/reflect c :reflector r))
                       (source-info class)
                       {:name       (-> c .getSimpleName symbol)
                        :class      (-> c .getName symbol)
                        ;; Classes defined by deftype and defrecord don't have a package
                        ;; this is probably a Clojure bug
                        :package    (if-let [p (.getPackage c)] (-> p .getName symbol))
                        :super      (-> c .getSuperclass typesym)
                        :interfaces (map typesym (.getInterfaces c))
                        :javadoc    (javadoc-url class)}))))


;;; ## Class Metadata Caching
;; When it won't change, cache the class info. Otherwise when we analyze
;; hundreds or more classes at once (as with a naive symbol resolution call),
;; duplicated reflection and source parsing becomes a wasteful performance hit.
;;
;; To support mixed Clojure/Java projects where `.java` files are being updated
;; and recompiled, we cache classes for which the Java source is in a jar/zip or
;; is not present, but don't cache when the `.java` file is on the classpath.

(def cache (atom {}))
(defn cache?
  "Whether to cache the class info; this will be true if the source file is
  effectively immutable, and false otherwise. Specifically, this returns true if
  no source file is available, or if the source file is in a jar/zip archive."
  [info]
  (let [path (:file info)
        src  (when path (io/resource path))]
    (or (not src)
        (re-find #"\.(jar|zip)!" (str src)))))

(defn class-info
  "For the class symbol, return (possibly cached) Java class and member info.
  Members are indexed first by name, and then by argument types to list all
  overloads."
  [class]
  (or (@cache class)
      (let [info (class-info* class)]
        (when (cache? info)
          (swap! cache assoc class info))
        info)))


;;; ## Class/Member Info
;; These functions filter the class info assembled above to respond to a more
;; specific query: type information for a class name, and member information for
;; a class/member combination.

(defn type-info
  "For the class or interface symbol, return Java type info. If the type has
  defined contructors, the line and column returned will be for the first of
  these for more convenient `jump` navigation."
  [class]
  (let [info (class-info class)
        ctor (->> (get-in info [:members class])
                  (vals)
                  (sort-by :line)
                  (filter :line)
                  (first))]
    (merge (dissoc info :members)
           (select-keys ctor [:line :column]))))

(defn member-info
  "For the class and member symbols, return Java member info. If the member is
  overloaded, line number and javadoc signature are that of the first overload.
  If the member's definition is in a superclass, info returned will be for the
  implemention. If the member is an instance member, `this` is prepended to its
  arglists."
  [class member]
  (let [c (->> (class-info class)
               (iterate (comp class-info :super))
               (take-while identity)
               (filter #(get-in % [:members member]))
               (first))]
    (when-let [m (get-in c [:members member])]
      (let [m* (first (sort-by :line (vals m)))
            static? (or (:static (:modifiers m*)) (= class member))
            +this   (comp vec (partial cons 'this))]
        (-> (dissoc m* :name :argnames)
            (assoc :class class
                   :member member
                   :file (:file c)
                   :arglists (map #((if static? identity +this)
                                    (or (:argnames %) (:argtypes %)))
                                  (vals m))
                   :javadoc (javadoc-url class member
                                         (:argtypes m*))))))))


;;; ## Class/Member Resolution
;; A namespace provides a search context for resolving a symbol to a Java class
;; or member. Classes, constructors, and static members can be resolved
;; unambiguously. With instance members, more than one imported class may have
;; a member that matches the searched symbol. In such cases, the result will be
;; a list of resolved candidate members. (Note that this list could be narrowed
;; by considering arity, and narrowed further *if* we could consider argument
;; types...)

(defn resolve-class
  "Given namespace and class symbols, search the imported classes and return
  class info. If not found, search all classes on the classpath (requires a
  qualified name)."
  [ns sym]
  (when-let [ns (find-ns ns)]
    (let [c (try (ns-resolve ns sym)
                 (catch Exception _))]
      (if (class? c)
        (class-info (-> ^Class c .getName symbol))
        (class-info sym)))))

(defn resolve-member
  "Given namespace and member symbols, search the imported classes and return
  a list of each matching member's info."
  [ns sym]
  (when-let [ns (find-ns ns)]
    (->> (vals (ns-imports ns))
         (map #(member-info (-> ^Class % .getName symbol) sym))
         (filter identity)
         (distinct))))

(defn resolve-symbol
  "Given a namespace and a class or member symbol, resolve the class/member.
  Class symbols, constructors, and static calls are resolved to the class
  unambiguously. Instance members are resolved unambiguously if defined by only
  one imported class. If multiple imported classes have a member by that name, a
  map of class names to member info is returned as `:candidates`."
  [ns sym]
  {:pre [(every? symbol? [ns sym])]}
  (let [name (-> (str sym)
                 (str/replace #"^\.|\.$" "")) ; strip leading/trailing dot
        sym* (symbol name)
        [class static-member] (->> (str/split name #"/" 2)
                                   (map #(when % (symbol %))))]
    (if-let [c (resolve-class ns class)]
      (if static-member
        (member-info (:class c) static-member)      ; SomeClass/methodCall
        (type-info (:class c)))                     ; SomeClass
      (when-let [ms (seq (resolve-member ns sym*))] ; methodCall
        (if (= 1 (count ms))
          (first ms)
          {:candidates (zipmap (map :class ms) ms)})))))


;;; ## Initialization
;; On startup, cache info for the most commonly referenced classes.
(future
  (doseq [class (->> (ns-imports 'clojure.core)
                     (map #(-> % ^Class val .getName symbol)))]
    (class-info class)))
