(ns cider.nrepl.middleware.resource
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.misc :as u]
   [orchard.resource :as resource]))

(defn handle-resource [handler msg]
  (with-safe-transport handler msg
    "resource" {:resource-path (resource/resource-path (:name msg))}
    "resources-list" {:resources-list (u/transform-value (resource/resource-maps))}))
