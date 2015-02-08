(ns cider.nrepl.middleware.format
  (:require [cljfmt.core :as fmt]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]])
  (:import [java.net URLDecoder]))

(defn format-code-reply
  [{:keys [transport code] :as msg}]
  (transport/send
   transport
   (response-for msg
                 :formatted-code (fmt/reformat-string code)
                 :status :done)))

(defn wrap-format
  "Middleware that provides code formatting."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "format-code" op)
      (format-code-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-format
 {:handles
  {"format-code"
   {:doc "Format a Clojure source file."
    :requires {"code" "The code to format"}
    :returns {"formatted-code" "The formatted code."}}}})
