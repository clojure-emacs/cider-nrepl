(ns cider.nrepl.middleware.complete
  (:require [clojure.string :as s]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :as u]
            [complete.core :as jvm-complete]
            [cljs-tooling.complete :as cljs-complete]))

(defn complete
  [{:keys [symbol ns public-only? case-sensitive? prefer-ns] :as msg}]
  (let [ns (u/as-sym ns)
        prefix (str symbol)]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (cljs-complete/completions cljs-env prefix ns)
      (jvm-complete/completions prefix ns))))

(defn complete-reply
  [{:keys [transport] :as msg}]
  (let [results (complete msg)]
    (transport/send transport (response-for msg :value results))
    (transport/send transport (response-for msg :status :done))))

(defn wrap-complete
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "complete" op)
      (complete-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-complete
 (cljs/maybe-piggieback
  {:handles
   {"complete"
    {:doc "Return a list of symbols matching the specified (partial) symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}}}))

