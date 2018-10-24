(ns cider.nrepl.middleware.format
  "Code and EDN formatting functionality."
  (:refer-clojure :exclude [read-string])
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [cljfmt.core :as fmt]
   [clojure.string :as str]
   [clojure.tools.reader.edn :as edn]
   [clojure.tools.reader.reader-types :as readers]
   [clojure.walk :as walk]))

;;; Code formatting
(defn- keyword->symbol [kw]
  (.sym ^clojure.lang.Keyword kw))

(defn- generate-user-indents [indents]
  (reduce-kv
   (fn [acc kw rule]
     (assoc acc
            (keyword->symbol kw)
            (walk/postwalk #(cond-> % (string? %) keyword) rule)))
   fmt/default-indents
   indents))

(defn format-code-reply
  [{:keys [code options] :as msg}]
  (let [opts (some-> options
                     (select-keys [:indents :alias-map])
                     (update :indents generate-user-indents)
                     (update :alias-map #(reduce-kv (fn [m k v] (assoc m (name k) v)) {} %)))]
    {:formatted-code (fmt/reformat-string code opts)}))

;;; EDN formatting
(defn- read-edn
  "Returns a vector of EDN forms, read from the string s."
  [s]
  (let [reader (readers/string-push-back-reader s)
        sentinel (Object.)]
    (loop [forms []]
      (let [form (edn/read {:eof sentinel} reader)]
        (if (= sentinel form)
          forms
          (recur (conj forms form)))))))

(defn- format-edn
  [edn pprint-fn]
  (->> (read-edn edn)
       (map #(with-out-str (pprint-fn %)))
       str/join
       str/trim))

(defn format-edn-reply
  [{:keys [edn pprint-fn] :as msg}]
  {:formatted-edn (format-edn edn pprint-fn)})

;;; Middleware op handling
(defn handle-format [handler msg]
  (with-safe-transport handler msg
    "format-code" format-code-reply
    "format-edn"  format-edn-reply))
