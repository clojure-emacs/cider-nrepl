(ns cider.nrepl.middleware.xref
  "Find function dependencies and function references."
  {:author "Bozhidar Batsov"
   :added "0.22"}
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [clojure.java.io :as io]
   [orchard.xref :as xref]
   [orchard.meta :as meta]
   [orchard.misc :as misc]))

(defn xref-data [v]
  (let [var-meta (meta/var-meta v)]
    {:name (meta/var-name v)
     :doc (meta/var-doc 1 v)
     :file (:file var-meta)
     :line (:line var-meta)
     :column (:column var-meta)}))

(defn fn-refs-reply [{:keys [ns symbol]}]
  (let [var (ns-resolve (misc/as-sym ns) (misc/as-sym symbol))]
   {:fn-refs (map xref-data (xref/fn-refs var))}))

(defn fn-deps-reply [{:keys [ns symbol]}]
  (let [var (ns-resolve (misc/as-sym ns) (misc/as-sym symbol))]
    {:fn-deps (map xref-data (xref/fn-deps var))}))

(defn handle-xref [handler msg]
  (with-safe-transport handler msg
    "fn-refs" fn-refs-reply
    "fn-deps" fn-deps-reply))
