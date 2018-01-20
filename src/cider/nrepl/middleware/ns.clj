(ns cider.nrepl.middleware.ns
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [cider.nrepl.middleware.util.meta :as m]
            [orchard.misc :as u]
            [orchard.namespace :as ns]
            [cljs-tooling.info :as cljs-info]
            [cljs-tooling.util.analysis :as cljs-analysis]))

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

(defn ns-vars-with-meta-clj [ns]
  (->> (symbol ns)
       ns-interns
       (u/update-vals (comp m/relevant-meta meta))
       (u/update-keys name)
       (into (sorted-map))))

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

(defn ns-vars-with-meta-cljs [env ns]
  (->> (symbol ns)
       (cljs-analysis/public-vars env)
       (u/update-vals (comp m/relevant-meta :meta))
       (u/update-keys name)
       (into (sorted-map))))

(defn ns-path-cljs [env ns]
  (->> (symbol ns)
       (cljs-info/info env)
       (:file)))

(defn ns-list [{:keys [filter-regexps] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-list-cljs cljs-env)
    (ns/loaded-namespaces filter-regexps)))

(defn ns-vars [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-cljs cljs-env ns)
    (ns-vars-clj ns)))

(defn ns-vars-with-meta [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-with-meta-cljs cljs-env ns)
    (ns-vars-with-meta-clj ns)))

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

(defn ns-vars-with-meta-reply
  [msg]
  {:ns-vars-with-meta (ns-vars-with-meta msg)})

(defn- ns-path-reply [msg]
  {:path (ns-path msg)})

(defn- ns-load-all-reply
  [msg]
  {:loaded-ns (ns/load-project-namespaces)})

(defn handle-ns [handler msg]
  (with-safe-transport handler msg
    "ns-list" ns-list-reply
    "ns-list-vars-by-name" ns-list-vars-by-name-reply
    "ns-vars" ns-vars-reply
    "ns-vars-with-meta" ns-vars-with-meta-reply
    "ns-path" ns-path-reply
    "ns-load-all" ns-load-all-reply))
