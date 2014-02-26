(ns cider.nrepl.middleware.util.misc)

(defn as-sym
  [x]
  (if x (symbol x)))

(defmulti transform-value "Transform a value for output" type)

(defmethod transform-value :default [v] (str v))

(defmethod transform-value Number
  [v]
  v)

(defmethod transform-value java.lang.Boolean
  [v]
  (if v "true" "false"))

(defmethod transform-value java.io.File
  [v]
  (.getAbsolutePath v))

(defmethod transform-value java.util.regex.Pattern
  [v]
  (str v))

(defmethod transform-value clojure.lang.PersistentVector
  [v]
  (list* (map transform-value v)))

(defmethod transform-value clojure.lang.LazySeq
  [v]
  (list* (map transform-value v)))

(defmethod transform-value clojure.lang.PersistentHashMap
  [m]
  (list* (mapcat #(vector (name (key %)) (transform-value (val %))) m)))

(defmethod transform-value clojure.lang.PersistentArrayMap
  [m]
  (list* (mapcat #(vector (name (key %)) (transform-value (val %))) m)))

(defn args-for-map
  "Return a value list based on a map. The keys are converted to strings."
  [m]
  (transform-value m))

(defn read-when
  "Read from the string passed if it is not nil"
  [s]
  (when s (read-string s)))

