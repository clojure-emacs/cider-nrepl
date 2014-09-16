(ns cider.nrepl.middleware.classpath
  (:require [clojure.java.classpath :as cp]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]])
  (:import [java.net URLDecoder]))

(defn classpath []
  (map #(URLDecoder/decode (str %) "utf-8") (cp/classpath)))

(defn classpath-reply
  [{:keys [transport] :as msg}]
  (transport/send
   transport
   (response-for msg
                 :classpath (classpath)
                 :status :done)))

(defn wrap-classpath
  "Middleware that provides the java classpath."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "classpath" op)
      (classpath-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-classpath
 {:handles
  {"classpath"
   {:doc "Obtain a list of entries in the Java classpath."
    :returns {"classpath" "A list of the Java classpath entries."}}}})
