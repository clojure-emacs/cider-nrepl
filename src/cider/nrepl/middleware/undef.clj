(ns cider.nrepl.middleware.undef
  "Undefine a symbol"
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [cider.nrepl.middleware.util.misc :as u]))

(defn undef
  [{:keys [ns symbol] :as msg}]
  (let [[ns symbol] (map u/as-sym [ns symbol])]
    (ns-unalias ns symbol)
    (ns-unmap ns symbol)
    symbol))

(defn undef-reply
  [msg]
  {:undef (undef msg)})

(defn handle-undef [handler msg]
  (with-safe-transport handler msg
    "undef" undef-reply))
