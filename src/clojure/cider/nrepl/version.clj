(ns cider.nrepl.version
  ;; We require print-method here because `cider.nrepl.version`
  ;; namespace is used by every connection.
  (:require
   [cider.nrepl.print-method]
   [clojure.java.io :as io]))

(def version-string
  "The current version for cider-nrepl as a string."
  (-> (or (io/resource "cider/cider-nrepl/project.clj")
          "project.clj")
      slurp
      read-string
      (nth 2)))

(def version
  "Current version of CIDER nREPL as a map.
  Map of :major, :minor, :incremental, :qualifier,
  and :version-string."
  (assoc (->> version-string
              (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)")
              rest
              (map #(try (Integer/parseInt %) (catch Exception e nil)))
              (zipmap [:major :minor :incremental :qualifier]))
         :version-string version-string))

(defn cider-version-reply
  "Returns CIDER-nREPL's version as a map which contains `:major`,
  `:minor`, `:incremental`, and `:qualifier` keys, just as
  `*clojure-version*` does."
  [msg]
  {:cider-version version})
