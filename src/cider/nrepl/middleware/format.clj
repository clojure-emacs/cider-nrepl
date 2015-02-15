(ns cider.nrepl.middleware.format
  (:refer-clojure :exclude [read-string])
  (:require [cider.nrepl.middleware.util.misc :refer [err-info]]
            [cljfmt.core :as fmt]
            [clojure.pprint :refer [pprint *print-right-margin*]]
            [clojure.string :as string]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.reader.edn :as edn]
            [clojure.tools.reader.reader-types :as readers])
  (:import [java.net URLDecoder]))

(defn format-code-reply
  [{:keys [transport code] :as msg}]
  (transport/send
   transport
   (response-for msg
                 :formatted-code (fmt/reformat-string code)
                 :status :done)))

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
  [{:keys [edn right-margin] :as msg}]
  (binding [*print-right-margin* (or right-margin *print-right-margin*)]
    (->> (read-edn edn)
         (map #(with-out-str (pprint %)))
         string/join
         string/trim)))

(defn format-edn-reply
  [{:keys [transport] :as msg}]
  (try
    (transport/send
     transport
     (response-for msg :formatted-edn (format-edn msg) :status :done))
    (catch Exception e
      (transport/send
       transport
       (response-for msg (err-info e :edn-read-error))))))

(defn wrap-format
  "Middleware that provides code formatting."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "format-code" (format-code-reply msg)
      "format-edn" (format-edn-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-format
 {:handles
  {"format-code"
   {:doc "Format a Clojure source file."
    :requires {"code" "The code to format"}
    :returns {"formatted-code" "The formatted code."}}
   "format-edn"
   {:doc "Reformats the given EDN data, returning the result as a string."
    :requires {"edn" "The data to format."}
    :optional {"right-margin" "The maximum column width of the formatted result."}
    :returns {"formatted-edn" "The formatted data."}}}})
