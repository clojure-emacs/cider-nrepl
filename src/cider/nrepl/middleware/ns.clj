(ns cider.nrepl.middleware.ns
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :refer [err-info]]
            [cider.nrepl.middleware.util.namespace :as ns]
            [cljs-tooling.info :as cljs-info]
            [cljs-tooling.util.analysis :as cljs-analysis]
            [clojure.tools.nrepl
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]
             [transport :as transport]]))

(defn ns-list-vars-by-name
  "Return a list of vars named `name` amongst all namespaces.
  `name` is a symbol."
  [name]
  (->> (mapcat ns-interns (all-ns))
       (filter #(= (first %) name))
       (map second)))

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

(defn ns-path-cljs [env ns]
  (->> (symbol ns)
       (cljs-info/info env)
       (:file)))

(defn ns-list [msg]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-list-cljs cljs-env)
    (ns/loaded-namespaces)))

(defn ns-vars [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-cljs cljs-env ns)
    (ns-vars-clj ns)))

(defn ns-path [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-path-cljs cljs-env ns)
    (ns/ns-path ns)))

(defn ns-list-reply
  [{:keys [transport] :as msg}]
  (try
    (transport/send transport
                    (response-for msg :ns-list (ns-list msg) :status :done))
    (catch Exception e
      (transport/send transport
                      (response-for msg (err-info e :ns-list-reply-error))))))

(defn ns-list-vars-by-name-reply
  [{:keys [transport name] :as msg}]
  (try
    (->> (ns-list-vars-by-name (symbol name))
         pr-str
         (response-for msg :status :done :var-list)
         (transport/send transport))
    (catch Exception e
      (transport/send transport
                      (response-for msg (err-info e :ns-list-vars-by-name-reply-error))))))

(defn ns-vars-reply
  [{:keys [transport] :as msg}]
  (try
    (transport/send transport
                    (response-for msg :ns-vars (ns-vars msg) :status :done))
    (catch Exception e
      (transport/send transport
                      (response-for msg (err-info e :ns-vars-reply-error))))))

(defn- ns-path-reply
  [{:keys [transport ns] :as msg}]
  (try
    (transport/send transport
                    (response-for msg :path (ns-path msg) :status :done))
    (catch Exception e
      (transport/send transport
                      (response-for msg (err-info e :ns-path-reply-error))))))

(defn- ns-load-all-reply
  [{:keys [transport ns] :as msg}]
  (try
    (transport/send transport
                    (response-for msg :loaded-ns (ns/load-project-namespaces) :status :done))
    (catch Exception e
      (transport/send transport
                      (response-for msg (err-info e :ns-load-all-error))))))

(defn wrap-ns
  "Middleware that provides ns listing/browsing functionality."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "ns-list" (ns-list-reply msg)
      "ns-list-vars-by-name" (ns-list-vars-by-name-reply msg)
      "ns-vars" (ns-vars-reply msg)
      "ns-path" (ns-path-reply msg)
      "ns-load-all" (ns-load-all-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-ns
 (cljs/requires-piggieback
  {:handles
   {"ns-list"
    {:doc "Return a sorted list of all namespaces."
     :returns {"status" "done"}}
    "ns-list-vars-by-name"
    {:doc "Return a list of vars named `name` amongst all namespaces."
     :requires {"name" "The name to use."}
     :returns {"status" "done" "var-list" "The list obtained."}}
    "ns-vars"
    {:doc "Returns a sorted list of all vars in a namespace."
     :requires {"ns" "The namespace to browse."}
     :returns {"status" "done"}}
    "ns-path"
    {:doc "Returns the path to the file containing ns."
     :requires {"ns" "The namespace to find."}
     :return {"status" "done" "path" "The path to the file containing ns."}}
    "ns-load-all"
    {:doc "Loads all project namespaces."
     :return {"status" "done" "loaded-ns" "The list of ns that were loaded."}}}}))
