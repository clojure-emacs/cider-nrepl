(ns cider.nrepl.middleware.format
  (:refer-clojure :exclude [read-string])
  (:require [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [cider.nrepl.middleware.pprint :as pprint]
            [cljfmt.core :as fmt]
            [clojure.string :as string]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.reader.edn :as edn]
            [clojure.tools.reader.reader-types :as readers]))

(defn format-code-reply
  [{:keys [code] :as msg}]
  {:formatted-code (fmt/reformat-string code)})

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
       string/join
       string/trim))

(defn format-edn-reply
  [{:keys [edn pprint-fn] :as msg}]
  {:formatted-edn (format-edn edn pprint-fn)})

(defn wrap-format
  "Middleware that provides code formatting."
  [handler]
  (with-safe-transport handler
    "format-code" format-code-reply
    "format-edn"  format-edn-reply))

(set-descriptor!
 #'wrap-format
 {:requires #{#'pprint/wrap-pprint-fn}
  :handles
  {"format-code"
   {:doc "Reformats the given Clojure code, returning the result as a string."
    :requires {"code" "The code to format."}
    :returns {"formatted-code" "The formatted code."}}
   "format-edn"
   {:doc "Reformats the given EDN data, returning the result as a string."
    :requires {"edn" "The data to format."}
    :optional {"print-right-margin" "The maximum column width of the formatted result."
               "pprint-fn" "Fully qualified name of the print function to be used."}
    :returns {"formatted-edn" "The formatted data."}}}})
