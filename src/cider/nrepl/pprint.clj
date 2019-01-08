(ns cider.nrepl.pprint
  "Pretty-print related utilities.
  All functions here are simple wrappers that ensure a consistent API:

  * has one and two params signatures - object to print and a map of print options
  * functions return the printed object as a string"
  {:added "0.20.0"}
  (:require
   [clojure.pprint :as pp]))

(defn pprint
  "A simple wrapper around `clojure.pprint/write`.
  It provides an API compatible with what nREPL's
  pr-values middleware expects for printer functions."
  ([object]
   (pprint object {}))
  ([object opts]
   (let [opts (assoc opts :stream nil)]
     (apply pp/write object (vec (flatten (vec opts)))))))

(def ^:private fipp-printer
  (delay
   (do
     (require 'fipp.edn)
     (resolve 'fipp.edn/pprint))))

(defn fipp-pprint
  ([object]
   (fipp-pprint object {}))
  ([object opts]
   (with-out-str
     (@fipp-printer object opts))))

(def ^:private puget-printer
  (delay
   (do
     (require 'puget.printer)
     (resolve 'puget.printer/pprint-str))))

(defn puget-pprint
  ([object]
   (puget-pprint object {}))
  ([object opts]
   (@puget-printer object opts)))
