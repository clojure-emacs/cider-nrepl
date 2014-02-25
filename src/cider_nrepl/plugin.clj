(ns cider-nrepl.plugin
  (:require [clojure.java.io :as io]))

(def ^:private version
  (-> (io/resource "META-INF/leiningen/cider/cider-nrepl/project.clj")
       slurp
       read-string
       (nth 2)))

(assert (string? version)
        (str "Something went wrong, version is not a string: "
             version))

(defn middleware
  [project]
  (-> project
      (update-in [:dependencies]
                 (fnil into [])
                 [['cider/cider-nrepl version]])
      (update-in [:repl-options :nrepl-middleware]
                 (fnil into [])
                 '[cider.nrepl.middleware.doc/wrap-doc
                   cider.nrepl.middleware.complete/wrap-complete
                   cider.nrepl.middleware.info/wrap-info
                   cider.nrepl.middleware.inspect/wrap-inspect])))
