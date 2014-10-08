(ns cider.nrepl.middleware.eldoc
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :as u]
            [cider.nrepl.middleware.info :as info]
            [clojure.repl :as repl]
            [cljs-tooling.info :as cljs-info]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn extract-eldoc [info]
  (if (contains? info :candidates)
    (->> (:candidates info)
         vals
         (mapcat :arglists)
         distinct
         (sort-by count))
    (:arglists info)))

(defn format-eldoc [raw-eldoc]
  (map #(mapv str %) raw-eldoc))

(defn eldoc
  [{:keys [ns symbol class member] :as msg}]
  (let [[ns symbol class member] (map u/as-sym [ns symbol class member])]
    (if-let [raw-eldoc (extract-eldoc (info/info msg))]
      (format-eldoc raw-eldoc))))

(defn eldoc-reply
  [{:keys [transport] :as msg}]
  (try
    (if-let [var-eldoc (eldoc msg)]
      (transport/send
       transport (response-for msg {:eldoc var-eldoc :status :done}))
      (transport/send
       transport (response-for msg {:status #{:no-eldoc :done}})))
    (catch Exception e
      (transport/send
       transport (response-for msg (u/err-info e :eldoc-error))))))

(defn wrap-eldoc
  "Middleware that looks up eldoc for a symbol within the context of a particular namespace."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "eldoc" op)
      (eldoc-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-eldoc
 (cljs/maybe-piggieback
  {:handles
   {"eldoc"
    {:doc "Return a map of information about the specified symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}}}))
