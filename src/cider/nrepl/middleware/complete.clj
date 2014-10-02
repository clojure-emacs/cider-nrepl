(ns cider.nrepl.middleware.complete
  (:require [clojure.string :as s]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :as u]
            [compliment.core :as jvm-complete]
            [cljs-tooling.complete :as cljs-complete]))

(defn complete
  [{:keys [symbol ns context] :as msg}]
  (let [ns (u/as-sym ns)
        prefix (str symbol)]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (cljs-complete/completions cljs-env prefix ns)
      (jvm-complete/completions prefix ns context))))

(defn completion-doc
  [{:keys [symbol ns] :as msg}]
  (when-not (cljs/grab-cljs-env msg)
    (jvm-complete/documentation (str symbol) (u/as-sym ns))))

(defn complete-reply
  [{:keys [transport] :as msg}]
  (try
    (transport/send
     transport
     (response-for msg :completions (complete msg) :status :done))
    (catch Exception e
      (transport/send
       transport
       (response-for msg (u/err-info e :completion-error))))))

(defn doc-reply
  [{:keys [transport] :as msg}]
  (let [results (completion-doc msg)]
    (transport/send transport (response-for msg :completion-doc results :status :done))))

(defn wrap-complete
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (cond
     (= "complete" op) (complete-reply msg)
     (= "complete-doc" op) (doc-reply msg)
     :else (handler msg))))

(set-descriptor!
 #'wrap-complete
 (cljs/maybe-piggieback
  {:handles
   {"complete"
    {:doc "Return a list of symbols matching the specified (partial) symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The symbol's namespace"
                "session" "The current session"}
     :optional {"context" "Completion context for compliment."}
     :returns {"completions" "A list of possible completions"}}
    "complete-doc"
    {:doc "Retrieve documentation suitable for display in completion popup"
     :requires {"symbol" "The symbol to lookup"
                "ns" "The symbol's namespace"}
     :returns {"completion-doc" "Symbol's documentation"}}}}))
