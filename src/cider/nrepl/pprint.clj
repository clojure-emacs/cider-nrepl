(ns cider.nrepl.pprint
  "Pretty-print related utilities.
  All functions here are simple wrappers that ensure a consistent API:

  * two params - object to print and a map of print options
  * the keys of the print options map can be strings, as bencode clients can't send keywords
  * functions return the printed object as a string"
  {:added "0.20.0"}
  (:require
   [clojure.pprint :as pp]
   [clojure.walk :as walk]))

(defn pprint
  "A simple wrapper around `clojure.pprint/write`.
  It provides an API compatible with what nREPL's
  pr-values middleware expects for printer functions."
  [object opts]
  (let [opts (assoc (walk/keywordize-keys opts) :stream nil)]
    (apply pp/write object (vec (flatten (vec opts))))))

(def ^:private fipp-printer
  (delay
   (do
     (require 'fipp.edn)
     (resolve 'fipp.edn/pprint))))

(defn fipp-pprint [object opts]
  (with-out-str
    (@fipp-printer object (walk/keywordize-keys opts))))

(def ^:private puget-printer
  (delay
   (do
     (require 'puget.printer)
     (resolve 'puget.printer/pprint-str))))

(defn puget-pprint [object opts]
  (@puget-printer object (walk/keywordize-keys opts)))
