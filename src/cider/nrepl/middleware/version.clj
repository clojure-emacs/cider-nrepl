(ns cider.nrepl.middleware.version
  "Return version info of the CIDER-nREPL middleware itself."
  (:require
   [cider.nrepl.version :as version]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]))

(defn handle-version [handler msg]
  (if (= (:op msg) "cider-version")
    (->> {:cider-version version/version}
         (merge {:status #{"done"}})
         (response-for msg)
         (transport/send (:transport msg)))
    (handler msg)))
