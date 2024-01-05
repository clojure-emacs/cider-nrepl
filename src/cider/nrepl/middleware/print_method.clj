(ns cider.nrepl.middleware.print-method
  (:require
   [cider.nrepl.print-method :refer [*pretty-objects*]]))

(defn handle-print-method [handler msg]
  (binding [*pretty-objects* true]
    (handler msg)))
