(ns cider.nrepl.middleware.util.misc)

(defn as-sym
  [x]
  (if x (symbol x)))

(defmulti transform-value "Transform a value for output" type)

(defmethod transform-value :default [v] (str v))

(defmethod transform-value Number [v] v)

(defmethod transform-value nil [v] nil)

(defmethod transform-value java.io.File
  [v]
  (.getAbsolutePath v))

(defmethod transform-value clojure.lang.Sequential
  [v]
  (list* (map transform-value v)))

(defmethod transform-value clojure.lang.Associative
  [m]
  (->> (for [[k v] m]
         [k (transform-value v)])
       (into {})))

;; handles vectors
(prefer-method transform-value clojure.lang.Sequential clojure.lang.Associative)

