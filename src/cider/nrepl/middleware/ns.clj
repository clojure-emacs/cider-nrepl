(ns cider.nrepl.middleware.ns
  (:refer-clojure :exclude [ns-aliases])
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.coerce :as util.coerce]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [cider.nrepl.middleware.util.meta :as um]
   [orchard.cljs.analysis :as cljs-analysis]
   [orchard.info :as info]
   [orchard.misc :as u]
   [orchard.namespace :as ns]
   [orchard.query :as query]))

(defn ns-list-vars-by-name
  "Return a list of vars named `name` amongst all namespaces.
  `name` is a symbol."
  [name]
  (->> (mapcat ns-interns (all-ns))
       (filter #(= (first %) name))
       (map second)))

(defn ns-vars-clj [ns & [var-query]]
  (->> {:ns-query {:exactly [ns]}}
       (merge var-query)
       util.coerce/var-query
       query/vars
       (map (comp str :name meta))
       sort))

(defn ns-vars-with-meta-clj [ns & [var-query]]
  (->> {:ns-query {:exactly [ns]}}
       (merge var-query)
       util.coerce/var-query
       query/vars
       (map meta)
       (map (juxt (comp str :name) um/relevant-meta))
       (into (sorted-map))))

(defn ns-list-cljs [env]
  (->> (cljs-analysis/all-ns env)
       keys
       (map name)
       sort))

(defn ns-vars-cljs [env ns & [var-query]]
  (let [fetch-vars (if (:private? var-query)
                     (partial cljs-analysis/ns-interns-from-env env)
                     (partial cljs-analysis/public-vars env))]
    (->> (symbol ns)
         fetch-vars
         keys
         (map name)
         sort)))

(defn ns-vars-with-meta-cljs [env ns & [var-query]]
  (let [fetch-vars (if (:private? var-query)
                     (partial cljs-analysis/ns-interns-from-env env)
                     (partial cljs-analysis/public-vars env))]
    (->> (symbol ns)
         fetch-vars
         (u/update-vals (comp um/relevant-meta :meta))
         (u/update-keys name)
         (into (sorted-map)))))

(defn ns-list [{:keys [filter-regexps] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-list-cljs cljs-env)
    (ns/loaded-namespaces filter-regexps)))

(defn ns-vars [{:keys [ns var-query] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-cljs cljs-env ns var-query)
    (ns-vars-clj ns var-query)))

(defn ns-vars-with-meta [{:keys [ns var-query] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-with-meta-cljs cljs-env ns var-query)
    (ns-vars-with-meta-clj ns var-query)))

(defn ns-path [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (:file (info/info* {:dialect :cljs
                        :env cljs-env
                        :sym (symbol ns)}))
    (str (ns/canonical-source ns))))

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

(defn- ns-aliases-clj [ns]
  (->> (symbol ns)
       (clojure.core/ns-aliases)
       (u/update-vals ns-name)
       (util/transform-value)))

(defn- ns-aliases-cljs [env ns]
  (->> (cljs-analysis/ns-aliases env ns)
       (remove (fn [[k v]] (= k v)))
       (into {})
       (util/transform-value)))

(defn ns-aliases [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-aliases-cljs cljs-env ns)
    (ns-aliases-clj ns)))

(defn- ns-aliases-reply [msg]
  {:ns-aliases (ns-aliases msg)})

(defn handle-ns [handler msg]
  (with-safe-transport handler msg
    "ns-list" ns-list-reply
    "ns-list-vars-by-name" ns-list-vars-by-name-reply
    "ns-vars" ns-vars-reply
    "ns-vars-with-meta" ns-vars-with-meta-reply
    "ns-path" ns-path-reply
    "ns-load-all" ns-load-all-reply
    "ns-aliases" ns-aliases-reply))
