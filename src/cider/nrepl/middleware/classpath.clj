(ns cider.nrepl.middleware.classpath
  (:require [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [orchard.misc :as u]
            [clojure.java.classpath :as cp]
            [clojure.string :as str]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]))

(defn classpath
  "Return a list of classpath entries for the current project.
  Takes into account the classpath trickery performed by Boot."
  []
  (if-let [classpath (u/boot-fake-classpath)]
    (str/split classpath #":")
    (map str (cp/classpath))))

(defn classpath-reply [msg]
  {:classpath (classpath)})

(defn handle-classpath [handler msg]
  (with-safe-transport handler msg
    "classpath" classpath-reply))
