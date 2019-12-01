(ns cider.nrepl.pprint
  "Pretty-print related utilities.
  All functions here are simple wrappers compatible with the expectations of
  nrepl.middleware.print/wrap-print."
  {:added "0.20.0"}
  (:refer-clojure :exclude [pr])
  (:require
   [clojure.pprint :as pp]))

(def ^:private pr-options
  [:print-dup
   :print-readably
   :print-length
   :print-level
   :print-meta
   :print-namespace-maps])

(defn- option->var
  [option]
  (resolve (symbol "clojure.core" (str "*" (name option) "*"))))

(defn- pr-bindings
  [options]
  (->> (select-keys options pr-options)
       (into {} (keep (fn [[option value]]
                        (when-let [var (option->var option)]
                          [var value]))))))

(defn pr
  "Equivalent to `clojure.core/pr`. Any options corresponding to dynamic
  printing configuration vars in `clojure.core` will, if provided, be bound
  accordingly (e.g. `clojure.core/*print-length*` will be used if
  `:print-length` is provided)."
  ([value writer]
   (pr value writer nil))
  ([value writer options]
   (with-bindings (pr-bindings options)
     (if *print-dup*
       (print-dup value writer)
       (print-method value writer)))))

(defn pprint
  "A simple wrapper around `clojure.pprint/write`."
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
