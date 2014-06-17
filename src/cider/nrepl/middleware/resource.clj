(ns cider.nrepl.middleware.resource
  (:require [clojure.java.io :as io]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn resource-path [name]
  (when-let [resource (io/resource name)]
    (.getPath resource)))

(defn resource-reply
  [{:keys [name transport] :as msg}]
  (transport/send transport (response-for msg :value (resource-path name)))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-resource
  "Middleware that provides the path to resource."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "resource" op)
      (resource-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-resource
 {:handles
  {"resource"
   {:doc "Return the path to a resource."
    :returns {"status" "done"}}}})
