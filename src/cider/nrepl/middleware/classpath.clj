(ns cider.nrepl.middleware.classpath
  (:require [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [clojure.java.classpath :as cp]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]))

(defn classpath []
  (map str (cp/classpath)))

(defn classpath-reply [msg]
  {:classpath (classpath)})

(defn wrap-classpath
  "Middleware that provides the java classpath."
  [handler]
  (with-safe-transport handler
    "classpath" classpath-reply))

(set-descriptor!
 #'wrap-classpath
 {:handles
  {"classpath"
   {:doc "Obtain a list of entries in the Java classpath."
    :returns {"classpath" "A list of the Java classpath entries."}}}})
