(ns cider.nrepl.version)

(def version
  "Current version of CIDER nREPL, map of :major, :minor, :incremental, and :qualifier."
  (let [version-string "0.8.0"]
    (assoc (->> version-string
                (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)")
                rest
                (zipmap [:major :minor :incremental :qualifier]))
      :version-string version-string)))
