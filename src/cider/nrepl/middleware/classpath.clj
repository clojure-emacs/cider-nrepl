(ns cider.nrepl.middleware.classpath
  (:require [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [clojure.java.classpath :as cp]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]))

(defn classpath []
  (map str (cp/classpath)))

(defn classpath-reply [msg]
  {:classpath (classpath)})

(defn handle-classpath [handler msg]
  (with-safe-transport handler msg
    "classpath" classpath-reply))
