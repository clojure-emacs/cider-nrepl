(ns cider.nrepl.middleware.ns
  (:require [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cljs-tooling.util.analysis :as cljs-analysis]))

(defn ns-list-clj []
  (->> (all-ns)
       (map ns-name)
       (map name)
       (sort)))

(defn ns-vars-clj [ns]
  (->> (symbol ns)
       ns-publics
       keys
       (map name)
       sort))

(defn ns-list-cljs [env]
  (->> (cljs-analysis/all-ns env)
       keys
       (map name)
       sort))

(defn ns-vars-cljs [env ns]
  (->> (symbol ns)
       (cljs-analysis/public-vars env)
       keys
       (map name)
       sort))

(defn ns-list [msg]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-list-cljs cljs-env)
    (ns-list-clj)))

(defn ns-vars [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-cljs cljs-env ns)
    (ns-vars-clj ns)))

(defn ns-list-reply
  [{:keys [transport] :as msg}]
  (transport/send transport (response-for msg :value (ns-list msg)))
  (transport/send transport (response-for msg :status :done)))

(defn ns-vars-reply
  [{:keys [transport] :as msg}]
  (transport/send transport (response-for msg :value (ns-vars msg)))
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
 (cljs/maybe-piggieback
  {:handles
   {"ns-list"
    {:doc "Return a sorted list of all namespaces."
     :returns {"status" "done"}}
    "ns-vars"
    {:doc "Returns a sorted list of all vars in a namespace."
     :requires {"ns" "The namespace to browse"}
     :returns {"status" "done"}}}}))
