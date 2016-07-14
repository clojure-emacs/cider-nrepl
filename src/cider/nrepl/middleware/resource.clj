(ns cider.nrepl.middleware.resource
  (:require [clojure.java.io :as io]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport] :as err]
            [compliment.sources.resources :as r]
            [compliment.core :as jvm-complete]))

(defn resource-path [name]
  (when-let [resource (io/resource name)]
    (.getPath resource)))

(defn resources-list [{:keys [prefix context] :as msg
                       :or {context "(resource \"__prefix__\")" prefix ""}}]
  (->> (jvm-complete/completions prefix {:context context
                                         :sources [::r/resources]})
       (map :candidate)))

(defn resource-reply [{:keys [name] :as msg}]
  {:resource-path (resource-path name)})

(defn resources-list-reply [msg]
  {:resources-list (resources-list msg)})

(defn wrap-resource
  "Middleware that provides the path to resource."
  [handler]
  (with-safe-transport handler
    "resource" resource-reply
    "resources-list" resources-list-reply))

(set-descriptor!
 #'wrap-resource
 {:handles
  {"resource"
   {:doc "Obtain the path to a resource."
    :requires {"name" "The name of the resource in question."}
    :returns {"resource-path" "The file path to a resource."}}
   "resources-list"
   {:doc "Obtain a list of all resources on the classpath."
    :returns {"resources-list" "The list of resources."}
    :optional {"context" "Completion context for compliment."
               "prefix" "Prefix to filter out resources."}}}})
