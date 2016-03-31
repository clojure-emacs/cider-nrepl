(ns cider.nrepl.middleware.ns
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [cider.nrepl.middleware.util.namespace :as ns]
            [cljs-tooling.info :as cljs-info]
            [cljs-tooling.util.analysis :as cljs-analysis]
            [clojure.tools.nrepl
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]]))

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

(defn ns-list-reply [msg]
  {:ns-list (ns-list msg)})

(defn ns-list-vars-by-name-reply [{:keys [name] :as msg}]
  {:var-list (pr-str (ns-list-vars-by-name (symbol name)))})

(defn ns-vars-reply
  [msg]
  {:ns-vars (ns-vars msg)})

(defn- ns-path-reply [msg]
  {:path (ns-path msg)})

(defn- ns-load-all-reply
  [msg]
  {:loaded-ns (ns/load-project-namespaces)})

(defn wrap-ns
  "Middleware that provides ns listing/browsing functionality."
  [handler]
  (with-safe-transport handler
    "ns-list" ns-list-reply
    "ns-list-vars-by-name" ns-list-vars-by-name-reply
    "ns-vars" ns-vars-reply
    "ns-path" ns-path-reply
    "ns-load-all" ns-load-all-reply))

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
