(ns cider.nrepl.middleware.classpath
  (:require [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [clojure.java.classpath :as cp]
            [clojure.string :as str]
            [orchard.misc :as u])
  (:import [java.io File]))

(defn classpath
  "Return a list of classpath entries for the current project.
  Takes into account the classpath trickery performed by Boot."
  []
  (if-let [classpath (u/boot-fake-classpath)]
    (str/split classpath (re-pattern File/pathSeparator))
    (if (neg? (compare u/java-api-version "9"))
      (map str (cp/classpath))
      (-> (System/getProperty "java.class.path")
          (str/split (re-pattern File/pathSeparator))))))

(defn classpath-reply [msg]
  {:classpath (classpath)})

(defn handle-classpath [handler msg]
  (with-safe-transport handler msg
    "classpath" classpath-reply))
