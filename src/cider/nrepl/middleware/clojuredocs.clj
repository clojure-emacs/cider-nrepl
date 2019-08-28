(ns cider.nrepl.middleware.clojuredocs
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.clojuredocs :as docs]))

(defn- clojuredocs-lookup-reply [{:keys [export-edn-url ns sym]}]
  (try
    (if-let [doc (if export-edn-url
                   ;; TODO: change this to `resolve-doc` once I've added the extra arity there
                   (docs/resolve-and-find-doc (symbol ns) (symbol sym) export-edn-url)
                   (docs/resolve-and-find-doc (symbol ns) (symbol sym)))]
      {:clojuredocs (util/transform-value doc)}
      {:status :no-doc})
    ;; TODO: Handle a missing ns directly in Orchard
    (catch Exception e
      {:status :no-doc})))

(defn clojuredocs-refresh-cache-reply [{:keys [export-edn-url]}]
  (docs/clean-cache!)
  (if export-edn-url
    (docs/load-cache! export-edn-url)
    (docs/load-cache!))
  {:status :ok})

(defn handle-clojuredocs [handler msg]
  (with-safe-transport handler msg
    "clojuredocs-refresh-cache" clojuredocs-refresh-cache-reply
    "clojuredocs-lookup" clojuredocs-lookup-reply))
