(ns cider.nrepl.middleware.util.meta
  "Utility functions for dealing with metadata."
  (:require [clojure.walk :as walk]
            [cider.nrepl.middleware.util.misc :as u]))

(defn merge-meta
  "Non-throwing version of (vary-meta obj merge metamap-1 metamap-2 ...).
  Like `vary-meta`, this only applies to immutable objects. For
  instance, this function does nothing on atoms, because the metadata
  of an `atom` is part of the atom itself and can only be changed
  destructively."
  {:style/indent 1}
  [obj & metamaps]
  (try
    (apply vary-meta obj merge metamaps)
    (catch Exception e obj)))

(defn strip-meta [form]
  (if (meta form)
    (try
      (with-meta form nil)
      (catch Exception e form))
    form))

(defn macroexpand-all
  "Like clojure.walk/macroexpand-all, but preserves and macroexpands
  metadata. Also store the original form (unexpanded and stripped of
  metadata) in the metadata of the expanded form under original-key."
  [form & [original-key]]
  (let [md (meta form)
        expanded (walk/walk #(macroexpand-all % original-key)
                            identity
                            (if (seq? form)
                              ;; Without this, `macroexpand-all`
                              ;; throws if called on `defrecords`.
                              (try (macroexpand form)
                                   (catch ClassNotFoundException e form))
                              form))]
    (if md
      ;; Macroexpand the metadata too, because sometimes metadata
      ;; contains, for example, functions. This is the case for
      ;; deftest forms.
      (merge-meta expanded
        (macroexpand-all md)
        (when original-key
          ;; We have to quote this, or it will get evaluated by
          ;; Clojure (even though it's inside meta).
          {original-key (list 'quote (strip-meta form))}))
      expanded)))

(def relevant-meta-keys
  "Metadata keys that are useful to us.
  This is used so that we don't crowd the ns cache with useless or
  redudant information, such as :name and :ns."
  [:indent :deprecated :macro :arglists :test
   :cider.nrepl.middleware.util.instrument/breakfunction
   :style/indent :clojure.tools.trace/traced])

(defn relevant-meta
  "Filter the entries in map m by `relevant-meta-keys` and non-nil values."
  [m]
  (->> (select-keys m relevant-meta-keys)
       (filter second)
       (u/update-vals pr-str)))
