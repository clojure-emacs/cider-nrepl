(ns cider-nrepl.plugin
  (:require [clojure.java.io :as io]
            [cider.nrepl :refer (cider-middleware)]))

;; Keep in sync with VERSION-FORM in project.clj
(defn- version
  []
  (let [v (-> (io/resource "META-INF/leiningen/cider/cider-nrepl/project.clj")
              slurp
              read-string
              (nth 2))]
    (assert (string? v)
        (str "Something went wrong, version is not a string: "
             v))
    v))

(defn middleware
  [project]
  (-> project
      (update-in [:dependencies]
                 (fnil into [])
                 [['cider/cider-nrepl (version)]])
      (update-in [:repl-options :nrepl-middleware]
                 (fnil into [])
                 cider-middleware)))
