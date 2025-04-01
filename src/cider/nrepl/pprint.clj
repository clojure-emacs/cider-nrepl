(ns cider.nrepl.pprint
  "Pretty-print related utilities.
  All functions here are simple wrappers compatible with the expectations of
  nrepl.middleware.print/wrap-print."
  {:added "0.20"}
  (:refer-clojure :exclude [pr])
  (:require
   [clojure.pprint :as pp]
   [orchard.misc :as misc]))

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

(defn- try-resolve [var-symbol pprinter-name]
  (or (misc/require-and-resolve var-symbol)
      (binding [*out* *err*]
        (println (format "Could not load %s namespace. To use %s pretty-printing with CIDER, add it to dependencies explicitly."
                         (namespace var-symbol) pprinter-name))
        nil)))

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
  (delay (requiring-resolve 'fipp.edn/pprint)))

(defn fipp-pprint
  ([value writer]
   (fipp-pprint value writer {}))
  ([value writer options]
   (binding [*out* writer]
     (@fipp-printer value options))))

(def ^:private puget-printer
  (delay (try-resolve 'puget.printer/pprint "Puget")))

(defn puget-pprint
  ([value writer]
   (puget-pprint value writer {}))
  ([value writer options]
   (if-some [puget @puget-printer]
     (binding [*out* writer]
       (puget value options))
     ;; Default ot clojure.pprint/pprint if Puget could not be loaded.
     (pprint value writer options))))

(def ^:private zprint-printer
  (delay (requiring-resolve 'zprint.core/zprint)))

(defn zprint-pprint
  ([value writer]
   (zprint-pprint value writer {}))
  ([value writer options]
   (binding [*out* writer]
     (@zprint-printer value options))))
