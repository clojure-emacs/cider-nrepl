(ns cider.nrepl.middleware.undef
  "Undefine a symbol"
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.misc :as misc]))

(defn undef
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
