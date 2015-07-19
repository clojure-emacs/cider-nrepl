(ns cider.nrepl.middleware.util.misc
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.namespace
             [file :refer [read-file-ns-decl]]
             [find :refer [clojure-sources-in-jar find-clojure-sources-in-dir]]]
            [clojure.java.classpath :as cp]
            [clojure.stacktrace :as stacktrace])
  (:import java.util.jar.JarFile))

(def java-api-version
  (try (-> (System/getProperty "java.version") (str/split #"\.") second)
       (catch Exception _ "7")))

(defn deep-merge
  "Merge maps recursively. When vals are not maps, last value wins."
  [& xs]
  (let [f (fn f [& xs]
            (if (every? map? xs)
              (apply merge-with f xs)
              (last xs)))]
    (apply f (filter identity xs))))

(defn as-sym
  [x]
  (cond
    (symbol? x) x
    (string? x) (if-let [[_ ns sym] (re-matches #"(.+)/(.+)" x)]
                  (symbol ns sym)
                  (symbol x))))

(defmulti transform-value "Transform a value for output" type)

(defmethod transform-value :default [v] (str v))

(defmethod transform-value Number [v] v)

(defmethod transform-value nil [v] nil)

(defmethod transform-value java.io.File
  [v]
  (.getAbsolutePath ^java.io.File v))

(defmethod transform-value clojure.lang.Sequential
  [v]
  (list* (map transform-value v)))

(defmethod transform-value clojure.lang.Symbol
  [v]
  (let [[the-ns the-name] [(namespace v) (name v)]]
    (if the-ns
      (str the-ns "/" the-name)
      the-name)))

(defmethod transform-value clojure.lang.Keyword
  [v]
  (transform-value (.sym ^clojure.lang.Keyword v)))

(defmethod transform-value clojure.lang.Associative
  [m]
  (->> (for [[k v] m] ; bencode keys must be strings
         [(str (transform-value k)) (transform-value v)])
       (into {})))

;; handles vectors
(prefer-method transform-value clojure.lang.Sequential clojure.lang.Associative)

(defn err-info
  [ex status]
  {:ex (str (class ex))
   :err (with-out-str (stacktrace/print-cause-trace ex))
   :status #{status :done}})

;;; Finding a namespace's file.
(defn- jar-file?
  "Returns true if file is a normal file with a .jar or .JAR extension."
  [f]
  (let [file (io/file f)]
    (and (.isFile file)
         (.endsWith (.. file getName toLowerCase) ".jar"))))

(defn- get-clojure-sources-in-jar
  [^JarFile jar]
  (let [path-to-jar (.getName jar)]
    (map #(str "jar:file:" path-to-jar "!/" %) (clojure-sources-in-jar jar))))

(defn- all-clj-files-on-cp []
  (let [dirs-on-cp (filter #(.isDirectory %) (cp/classpath))
        jars-on-cp (map #(JarFile. %) (filter jar-file? (cp/classpath)))]
    (concat (->> dirs-on-cp
                 (mapcat find-clojure-sources-in-dir)
                 (map #(.getAbsolutePath %)))
            (mapcat get-clojure-sources-in-jar jars-on-cp))))

(defn ns-path
  "Return the path to a file containing namespace `ns`.
  `ns` can be a Namespace object or the name of a namespace."
  [ns]
  (let [ns (if (instance? clojure.lang.Namespace ns)
             (ns-name ns) (symbol ns))]
    (loop [paths (all-clj-files-on-cp)]
      (when (seq paths)
        (let [file-ns (second (read-file-ns-decl (first paths)))]
          (if (= file-ns ns)
            (first paths)
            (recur (rest paths))))))))
