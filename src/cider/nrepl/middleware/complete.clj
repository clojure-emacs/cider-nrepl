(ns cider.nrepl.middleware.complete
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [cljs-tooling.complete :as cljs-complete]
   [compliment.core :as jvm-complete]
   [compliment.utils :as jvm-complete-utils]
   [orchard.misc :as u]
   [suitable.complete-for-nrepl :as suitable]))

(defn- cljs-complete
  [msg cljs-env ns prefix extra-metadata]
  (concat (cljs-complete/completions cljs-env prefix {:context-ns ns
                                                      :extra-metadata extra-metadata})
          (suitable/complete-for-nrepl msg)))

(defn complete
  [{:keys [ns symbol context extra-metadata] :as msg}]
  (let [ns (u/as-sym ns)
        prefix (str symbol)
        extra-metadata (set (map keyword extra-metadata))]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (cljs-complete msg cljs-env ns prefix extra-metadata)
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

(defn flush-caches-reply
  [msg]
  (jvm-complete-utils/flush-caches)
  {})

(defn handle-complete [handler msg]
  (with-safe-transport handler msg
    "complete" complete-reply
    "complete-doc" doc-reply
    "complete-flush-caches" flush-caches-reply))
