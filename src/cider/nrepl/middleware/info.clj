(ns cider.nrepl.middleware.info
  (:require [clojure.string :as s]
            [clojure.java.io :as io]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :as u]
            [clojure.repl]
            [cljs-tooling.info :as cljs-info]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn ns-meta
  [ns]
  (merge
   (meta ns)
   {:ns ns
    :file (-> (ns-publics ns)
              first
              second
              meta
              :file)
    :line 1}))

(defn maybe-protocol
  [info]
  (if-let [prot-meta (meta (:protocol info))]
    (merge info {:file (:file prot-meta)
                 :line (:line prot-meta)})
    info))

(defn info-clj
  [ns sym]
  (cond
   ;; sym is an alias for another ns
   ((ns-aliases ns) sym) (ns-meta ((ns-aliases ns) sym))
   ;; it's simply a full ns
   (find-ns sym) (ns-meta (find-ns sym))
   ;; it's a var
   (ns-resolve ns sym) (-> (ns-resolve ns sym) meta maybe-protocol)))

(defn info
  [{:keys [ns symbol] :as msg}]
  (let [[ns symbol] (map u/as-sym [ns symbol])]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (cljs-info/info cljs-env symbol ns)
      (info-clj ns symbol))))

(defn format-response
  [info]
  (-> info
      (update-in [:ns] str)
      (update-in [:file] (comp str io/resource))
      u/transform-value))

(defn info-reply
  [{:keys [transport] :as msg}]
  (transport/send transport (response-for msg :value (format-response (info msg))))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-info
  "Middleware that looks up info for a symbol within the context of a particular namespace."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "info" op)
      (info-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-info
 (cljs/maybe-piggieback
  {:handles
   {"info"
    {:doc "Return a map of information about the specified symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}}}))


