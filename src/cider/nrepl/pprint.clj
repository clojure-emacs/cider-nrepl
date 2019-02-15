(ns cider.nrepl.pprint
  "Pretty-print related utilities.
  All functions here are simple wrappers that ensure a consistent API:

  * has one and two params signatures - object to print and a map of print options
  * functions return the printed object as a string"
  {:added "0.20.0"}
  (:refer-clojure :exclude [pr])
  (:require
   [clojure.pprint :as pp]))

(defn pr
  ([value writer]
   (pr value writer nil))
  ([value writer _]
   (if *print-dup*
     (print-dup value writer)
     (print-method value writer))))

(defn pprint
  "A simple wrapper around `clojure.pprint/write`.

  Its signature is compatible with the expectations of nREPL's wrap-print
  middleware."
  ([value writer]
   (pprint value writer {}))
  ([value writer options]
   (apply pp/write value (mapcat identity (assoc options :stream writer)))))

(def ^:private fipp-printer
  (delay
   (do
     (require 'fipp.edn)
     (resolve 'fipp.edn/pprint))))

(defn fipp-pprint
  ([value writer]
   (fipp-pprint value writer {}))
  ([value writer options]
   (binding [*out* writer]
     (@fipp-printer value options))))

(def ^:private puget-printer
  (delay
   (do
     (require 'puget.printer)
     (resolve 'puget.printer/pprint))))

(defn puget-pprint
  ([value writer]
   (puget-pprint value writer {}))
  ([value writer options]
   (binding [*out* writer]
     (@puget-printer value options))))

(def ^:private zprint-printer
  (delay
   (do
     (require 'zprint.core)
     (resolve 'zprint.core/zprint))))

(defn zprint-pprint
  ([value writer]
   (zprint-pprint value writer {}))
  ([value writer options]
   (binding [*out* writer]
     (@zprint-printer value options))))
