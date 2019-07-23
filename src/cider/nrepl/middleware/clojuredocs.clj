(ns cider.nrepl.middleware.clojuredocs
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.clojuredocs :as docs]
   [orchard.misc :as misc]))

(defn- clojuredocs-lookup-reply [{:keys [export-edn-url ns symbol]}]
  (if-let [doc (if export-edn-url
                 (docs/find-doc ns symbol export-edn-url)
                 (docs/find-doc ns symbol))]
    {:clojuredocs (misc/transform-value doc)}
    {:status :no-document}))

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
