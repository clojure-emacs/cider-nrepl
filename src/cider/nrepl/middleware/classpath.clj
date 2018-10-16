(ns cider.nrepl.middleware.classpath
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.classpath :as cp]))

(defn classpath-reply [msg]
  {:classpath (map str (cp/classpath))})

(defn handle-classpath [handler msg]
  (with-safe-transport handler msg
    "classpath" classpath-reply))
