(ns cider.nrepl.middleware.resource
  (:require [clojure.java.io :as io]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
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

(defn handle-resource [handler msg]
  (with-safe-transport handler msg
    "resource" resource-reply
    "resources-list" resources-list-reply))
