(ns cider.nrepl.middleware.undef
  "Undefine a symbol"
  (:require
   [cider.nrepl.middleware.util.misc :as u]
   [clojure.tools.nrepl.transport :as transport]
   [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
   [clojure.tools.nrepl.misc :refer [response-for]]))

(defn undef
  [{:keys [ns symbol] :as msg}]
  (let [[ns symbol] (map u/as-sym [ns symbol])]
    (ns-unalias ns symbol)
    (ns-unmap ns symbol)))

(defn undef-reply
  [{:keys [transport] :as msg}]
  (try
    (undef msg)
    (catch Exception e
      (transport/send
       transport (response-for msg :exception (.getMessage e)))))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-undef
  "Middleware to undefine a symbol in a namespace."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "undef" op)
      (undef-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-undef
 {:handles
  {"undef"
   {:doc "Undefine a symbol"
    :requires {"symbol" "The symbol to undefine"
               "ns" "The current namespace"}
    :returns {"status" "done"}}}})
