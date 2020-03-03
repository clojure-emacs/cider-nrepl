(ns cider.nrepl.middleware.undef
  "Middleware for undefining symbols.
  Fully qualified symbols are interpreted as a var to be unmapped in its
  original namespace, whereas unqualified symbols are interpreted as both a var
  and ns alias to be unmapped from the current namespace."
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.misc :as misc]))

(defn undef
  "Undefines a symbol.
  When `symbol` is unqualified, it is interpreted as both an alias and var to be
  unmapped from the namespace `ns`.
  When qualified (eg. `foo/bar`), it is interpreted as a var to be unmapped in
  the namespace `foo`, which may be an alias in `ns`."
  [{:keys [ns symbol]}]
  (let [ns (misc/as-sym ns)
        [sym-ns sym-name] ((juxt (comp misc/as-sym namespace) misc/name-sym)
                           (misc/as-sym symbol))]
    (if sym-ns
      ;; fully qualified => var in other namespace
      (let [other-ns (get (ns-aliases ns) sym-ns sym-ns)]
        (ns-unmap other-ns sym-name))
      ;; unqualified => alias or var in current ns
      (do (ns-unalias ns sym-name)
          (ns-unmap ns sym-name)))
    symbol))

(defn undef-reply
  [msg]
  {:undef (undef msg)})

(defn handle-undef [handler msg]
  (with-safe-transport handler msg
    "undef" undef-reply))
