(ns cider.nrepl.version
  ;; We require print-method here because `cider.nrepl.version`
  ;; namespace is used by every connection.
  (:require [cider.nrepl.print-method]
            [trptcolin.versioneer.core :as v]))

(def version
  "Current version of CIDER nREPL, map of :major, :minor, :incremental, and :qualifier."
  (let [version-string (v/get-version "cider" "cider-nrepl" "1.0.0")]
    (assoc (->> version-string
                (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)")
                rest
                (zipmap [:major :minor :incremental :qualifier]))
           :version-string version-string)))

(defn cider-version-reply
  "Returns CIDER-nREPL's version as a map which contains `:major`,
  `:minor`, `:incremental`, and `:qualifier` keys, just as
  `*clojure-version*` does."
  [msg]
  {:cider-version version})
