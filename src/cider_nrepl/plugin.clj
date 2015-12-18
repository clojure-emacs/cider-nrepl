(ns cider-nrepl.plugin
  (:require [clojure.java.io :as io]
            [leiningen.core.main :as lein]))

;; Keep in sync with VERSION-FORM in project.clj
(defn- version
  []
  (let [v (-> (io/resource "cider/cider-nrepl/project.clj")
              slurp
              read-string
              (nth 2))]
    (assert (string? v)
            (str "Something went wrong, version is not a string: "
                 v))
    v))

(defn middleware
  [{:keys [dependencies] :as project}]
  (let [clojure-version-ok? (->> dependencies
                                 (some (fn [[id version & {:as opts}]]
                                         (and (= id 'org.clojure/clojure)
                                              (lein/version-satisfies? version "1.7.0")))))
        lein-version-ok? (lein/version-satisfies? (lein/leiningen-version) "2.5.2")]

    (when-not clojure-version-ok?
      (lein/warn "Warning: cider-nrepl requires Clojure 1.7 or greater."))
    (when-not lein-version-ok?
      (lein/warn "Warning: cider-nrepl requires Leiningen 2.5.2 or greater."))
    (when-not (and clojure-version-ok? lein-version-ok?)
      (lein/warn "Warning: cider-nrepl will not be included in your project."))

    (cond-> project
      (and clojure-version-ok? lein-version-ok?)
      (-> (update-in [:dependencies]
                     (fnil into [])
                     [['cider/cider-nrepl (version)]])
          (update-in [:repl-options :nrepl-middleware]
                     (fnil into [])
                     (do (require 'cider.nrepl)
                         @(resolve 'cider.nrepl/cider-middleware)))))))
