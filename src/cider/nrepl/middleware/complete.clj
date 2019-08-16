(ns cider.nrepl.middleware.complete
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [compliment.core :as complete]
   [compliment.utils :as complete-utils]
   [orchard.misc :as misc]
   [suitable.complete-for-nrepl :as suitable]
   [suitable.compliment.sources.cljs :as suitable-sources]))

(def clj-sources
  "Source keywords for Clojure completions."
  [:compliment.sources.special-forms/literals
   :compliment.sources.class-members/static-members
   :compliment.sources.ns-mappings/ns-mappings
   :compliment.sources.resources/resources
   :compliment.sources.keywords/keywords
   :compliment.sources.local-bindings/local-bindings
   :compliment.sources.class-members/members
   :compliment.sources.namespaces-and-classes/namespaces-and-classes
   :compliment.sources.special-forms/special-forms])

(def cljs-sources
  "Source keywords for ClojureScript completions."
  [::suitable-sources/cljs-source])

(defn complete
  [{:keys [ns symbol context extra-metadata enhanced-cljs-completion?] :as msg}]
  (let [prefix (str symbol)
        completion-opts {:ns (misc/as-sym ns)
                         :context context
                         :extra-metadata (set (map keyword extra-metadata))}]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (binding [suitable-sources/*compiler-env* cljs-env]
        (concat (complete/completions prefix (merge completion-opts {:sources cljs-sources}))
                (when enhanced-cljs-completion? (suitable/complete-for-nrepl msg))))
      (complete/completions prefix (merge completion-opts {:sources clj-sources})))))

(defn completion-doc
  [{:keys [ns symbol] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (binding [suitable-sources/*compiler-env* cljs-env]
      (complete/documentation (str symbol) (misc/as-sym ns) {:sources cljs-sources}))
    (complete/documentation (str symbol) (misc/as-sym ns) {:sources clj-sources})))

(defn complete-reply [msg]
  {:completions (complete msg)})

(defn doc-reply
  [msg]
  {:completion-doc (completion-doc msg)})

(defn flush-caches-reply
  [msg]
  (complete-utils/flush-caches)
  {})

(defn handle-complete [handler msg]
  (with-safe-transport handler msg
    "complete" complete-reply
    "complete-doc" doc-reply
    "complete-flush-caches" flush-caches-reply))
