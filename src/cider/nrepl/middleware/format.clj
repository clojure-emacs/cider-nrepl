(ns cider.nrepl.middleware.format
  (:refer-clojure :exclude [read-string])
  (:require [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [cider.nrepl.middleware.pprint :as pprint]
            [clojure.string :as string]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.reader.edn :as edn]
            [clojure.tools.reader.reader-types :as readers]))

;; cljfmt.core takes many milliseconds to load and isn't used every session
;; so delay loading it.
(def ^:private reformat-string
  (delay
   (do
     (require 'cljfmt.core)
     (resolve 'cljfmt.core/reformat-string))))

(defn format-code-reply
  [{:keys [code] :as msg}]
  {:formatted-code (@reformat-string code)})

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

(defn handle-format [handler msg]
  (with-safe-transport handler msg
    "format-code" format-code-reply
    "format-edn"  format-edn-reply))
