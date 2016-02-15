(ns cider.nrepl.middleware.classpath
  (:require [clojure.java.classpath :as cp]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]
            [cider.nrepl.middleware.util.misc :as u]))

(defn classpath []
  (map str (cp/classpath)))

(defn classpath-reply
  [{:keys [transport] :as msg}]
  (try
    (transport/send
     transport
     (response-for msg
                   :classpath (classpath)
                   :status :done))
    (catch Exception e
      (transport/send
       transport
       (response-for msg (u/err-info e :classpath-error))))))

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
