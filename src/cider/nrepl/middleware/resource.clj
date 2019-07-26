(ns cider.nrepl.middleware.resource
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.resource :as resource]))

(defn handle-resource [handler msg]
  (with-safe-transport handler msg
    "resource" {:resource-path (resource/resource-path (:name msg))}
    "resources-list" {:resources-list (util/transform-value (resource/resource-maps))}))
