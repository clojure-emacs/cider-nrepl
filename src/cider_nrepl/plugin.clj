(ns cider-nrepl.plugin
  "Provides a simple way to setup the CIDER nREPL middleware in
  Leiningen projects."
  (:require
   [cider.nrepl.version :refer [version-string]]
   [clojure.java.io :as io]
   [leiningen.core.main :as lein]))

(def min-lein-version "2.8.2")

;; Exists for the sole purpose of modifying the current project's metadata.
;; See https://github.com/technomancy/leiningen/blob/master/doc/PLUGINS.md#project-middleware
(defn middleware
  [{:keys [dependencies exclusions] :as project}]
  (let [lein-version-ok?    (lein/version-satisfies? (lein/leiningen-version) min-lein-version)
        clojure-excluded?   (some #(= % 'org.clojure/clojure) exclusions)
        clojure-version     (when-not clojure-excluded?
                              (->> dependencies
                                   (some (fn [[id version & _]]
                                           (when (= id 'org.clojure/clojure)
                                             version)))))
        clojure-version-ok? (cond clojure-excluded?
                                  ;; In this case the onus is on the user. A warning will be emitted
                                  ;; later, but we assume that the user will provide an appropriate
                                  ;; implementation.
                                  true

                                  (nil? clojure-version)
                                  ;; Lein 2.8.3+ uses Clojure 1.8 by default, which would be OK.
                                  lein-version-ok?

                                  :else
                                  ;; There is a Clojure version depended on, it must check out.
                                  (lein/version-satisfies? clojure-version "1.8.0"))]

    (when-not lein-version-ok?
      (lein/warn "Warning: cider-nrepl requires Leiningen 2.8.3 or greater."))
    (when-not clojure-version-ok?
      (lein/warn "Warning: cider-nrepl requires Clojure 1.8 or greater."))
    (when clojure-excluded?
      (lein/warn "Warning: Clojure is excluded, assuming an appropriate fork (Clojure 1.8 or later) is provided."))
    (when-not (and lein-version-ok? clojure-version-ok?)
      (lein/warn "Warning: cider-nrepl will not be included in your project."))

    (cond-> project
      (and clojure-version-ok? lein-version-ok?)
      (-> (update-in [:dependencies]
                     (fnil into [])
                     [['cider/cider-nrepl version-string]])
          (update-in [:repl-options :nrepl-middleware]
                     (fnil into [])
                     (do (require 'cider.nrepl)
                         @(resolve 'cider.nrepl/cider-middleware)))))))
