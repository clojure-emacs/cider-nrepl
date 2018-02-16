(ns cider.nrepl.middleware.util.meta
  "Utility functions for extracting and manipulating metadata."
  (:require [orchard.misc :as u]))

(def relevant-meta-keys
  "Metadata keys that are useful to us.
  This is used so that we don't crowd the ns cache with useless or
  redudant information, such as :name and :ns."
  [:indent :deprecated :macro :arglists :test :doc :fn
   :cider/instrumented :style/indent :clojure.tools.trace/traced])

(defn relevant-meta
  "Filter the entries in map m by `relevant-meta-keys` and non-nil values."
  [m]
  (->> (select-keys m relevant-meta-keys)
       (filter second)
       (u/update-vals pr-str)))
