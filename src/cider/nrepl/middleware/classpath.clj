(ns cider.nrepl.middleware.classpath
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [clojure.java.io :as io]
   [orchard.java.classpath :as cp]))

(defn classpath-reply [msg]
  {:classpath (->> (cp/classpath)
                   (map io/as-file)
                   (map str))})

(defn handle-classpath [handler msg]
  (with-safe-transport handler msg
    "classpath" classpath-reply))
