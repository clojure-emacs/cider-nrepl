(ns cider.nrepl.middleware.complete
  (:require [clojure.string :as s]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [cider.nrepl.middleware.util.misc :as u]
            [compliment.core :as jvm-complete]
            [cljs-tooling.complete :as cljs-complete]))

(defn complete
  [{:keys [ns symbol context extra-metadata] :as msg}]
  (let [ns (u/as-sym ns)
        prefix (str symbol)
        extra-metadata (set (map keyword extra-metadata))]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (cljs-complete/completions cljs-env prefix {:context-ns ns
                                                  :extra-metadata extra-metadata})
      (jvm-complete/completions prefix {:ns ns
                                        :context context
                                        :extra-metadata extra-metadata}))))

(defn completion-doc
  [{:keys [ns symbol] :as msg}]
  (when-not (cljs/grab-cljs-env msg)
    (jvm-complete/documentation (str symbol) (u/as-sym ns))))

(defn complete-reply [msg]
  {:completions (complete msg)})

(defn doc-reply
  [msg]
  {:completion-doc (completion-doc msg)})

(defn wrap-complete
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (with-safe-transport handler
    "complete" complete-reply
    "complete-doc" doc-reply))

(set-descriptor!
 #'wrap-complete
 (cljs/requires-piggieback
  {:handles
   {"complete"
    {:doc "Return a list of symbols matching the specified (partial) symbol."
     :requires {"ns" "The symbol's namespace"
                "symbol" "The symbol to lookup"
                "session" "The current session"}
     :optional {"context" "Completion context for compliment."
                "extra-metadata" "List of extra-metadata fields. Possible values: arglists, doc."}
     :returns {"completions" "A list of possible completions"}}
    "complete-doc"
    {:doc "Retrieve documentation suitable for display in completion popup"
     :requires {"ns" "The symbol's namespace"
                "symbol" "The symbol to lookup"}
     :returns {"completion-doc" "Symbol's documentation"}}}}))
