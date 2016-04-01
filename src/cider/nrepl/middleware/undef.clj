(ns cider.nrepl.middleware.undef
  "Undefine a symbol"
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [cider.nrepl.middleware.util.misc :as u]
   [clojure.tools.nrepl.middleware :refer [set-descriptor!]]))

(defn undef
  [{:keys [ns symbol] :as msg}]
  (let [[ns symbol] (map u/as-sym [ns symbol])]
    (ns-unalias ns symbol)
    (ns-unmap ns symbol)
    symbol))

(defn undef-reply
  [msg]
  {:undef (undef msg)})

(defn wrap-undef
  "Middleware to undefine a symbol in a namespace."
  [handler]
  (with-safe-transport handler
    "undef" undef-reply))

(set-descriptor!
 #'wrap-undef
 {:handles
  {"undef"
   {:doc "Undefine a symbol"
    :requires {"symbol" "The symbol to undefine"
               "ns" "The current namespace"}
    :returns {"status" "done"}}}})
