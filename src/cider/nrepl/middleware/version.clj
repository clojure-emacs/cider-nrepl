(ns cider.nrepl.middleware.version
  "Return version info of the CIDER-nREPL middleware itself."
  (:require [cider.nrepl.version :as version]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]))


(defn- cider-version-reply
  "Returns CIDER-nREPL's version as a map which contains `:major`,
  `:minor`, `:incremental`, and `:qualifier` keys, just as
  `*clojure-version*` does."
  [msg]
  {:cider-version version/version})

(defn wrap-version [handler]
  (fn [msg]
    (if (= (:op msg) "cider-version")
      (->> (cider-version-reply msg)
           (merge {:status #{"done"}})
           (response-for msg)
           (transport/send (:transport msg)))
      (handler msg))))

(set-descriptor!
  #'wrap-version
  {:doc "Provides CIDER-nREPL version information."
   :describe-fn #'cider-version-reply ;; For the "describe" op. Merged in `:aux`
   :handles
   {"cider-version"
    {:doc "Returns the version of the CIDER-nREPL middleware."
     :requires {}
     :returns {"cider-version" "CIDER-nREPL's version map."
               "status" "done"}}}})
