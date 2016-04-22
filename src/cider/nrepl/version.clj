(ns cider.nrepl.version
  ;; We require print-method here because `cider.nrepl.version`
  ;; namespace is used by every connection.
  (:require [cider.nrepl.print-method]))

(def version
  "Current version of CIDER nREPL, map of :major, :minor, :incremental, and :qualifier."
  (let [version-string "0.13.0-snapshot"]
    (assoc (->> version-string
                (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)")
                rest
                (zipmap [:major :minor :incremental :qualifier]))
           :version-string version-string)))
