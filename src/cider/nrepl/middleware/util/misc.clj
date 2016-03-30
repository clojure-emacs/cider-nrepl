(ns cider.nrepl.middleware.util.misc
  (:require [clojure.string :as str]
            [clojure.stacktrace :as stacktrace]
            [cider.nrepl.middleware.util.storage :as c-store]))

(defn boot-project? []
  ;; fake.class.path under boot contains the original directories with source
  ;; files, see https://github.com/boot-clj/boot/issues/249
  (not (nil? (System/getProperty "fake.class.path"))))

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
  [ex & statuses]
  (let [ex-key (.hashCode ex)]
    (c-store/add! ex-key ex)
    {:ex (str (class ex))
     :err (with-out-str (stacktrace/print-cause-trace ex))
     :status (set (conj statuses :done))
     :storage-key ex-key}))
