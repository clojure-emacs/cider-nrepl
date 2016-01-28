(ns cider.nrepl.middleware.util.meta
  "Utility functions for dealing with metadata."
  (:require [clojure.walk :as walk]))

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

(defn macroexpand-all
  "Like clojure.walk/macroexpand-all, but preserves and macroexpands
  metadata."
  [form]
  (let [md (meta form)
        expanded (walk/walk macroexpand-all
                            identity
                            (if (seq? form) (macroexpand form) form))]
    (if md
      ;; Macroexpand the metadata too, because sometimes metadata
      ;; contains, for example, functions. This is the case for
      ;; deftest forms.
      (merge-meta expanded
        (macroexpand-all md))
      expanded)))

(defn strip-meta [form]
  (if (meta form)
    (with-meta form nil)
    form))
