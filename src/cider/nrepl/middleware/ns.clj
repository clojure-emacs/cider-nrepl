(ns cider.nrepl.middleware.ns
  (:require [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn ns-list []
  (->> (all-ns)
       (map ns-name)
       (map name)
       (sort)))

(defn ns-vars [ns]
  (->> (symbol ns)
       ns-publics
       keys
       (map name)
       sort))

(defn ns-list-reply
  [{:keys [transport] :as msg}]
  (transport/send transport (response-for msg :value (ns-list)))
  (transport/send transport (response-for msg :status :done)))

(defn ns-vars-reply
  [{:keys [transport ns] :as msg}]
  (transport/send transport (response-for msg :value (ns-vars ns)))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-ns
  "Middleware that provides ns listing/browsing functionality."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "ns-list" (ns-list-reply msg)
      "ns-vars" (ns-vars-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-ns
 {:handles
  {"ns-list"
   {:doc "Return a sorted list of all namespaces."
    :returns {"status" "done"}}
   "ns-vars"
   {:doc "Return a sorted list of all vars in a namespace."
    :returns {"status" "done"}}}})
