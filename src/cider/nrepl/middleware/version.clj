(ns cider.nrepl.middleware.version
  "Return version info of the CIDER-nREPL middleware itself."
  (:require [cider.nrepl.version :as version]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]))

(defn handle-version [handler msg]
  (if (= (:op msg) "cider-version")
    (->> (version/cider-version-reply msg)
         (merge {:status #{"done"}})
         (response-for msg)
         (transport/send (:transport msg)))
    (handler msg)))
