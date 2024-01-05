(ns cider-nrepl.plugin
  "Provides a simple way to setup the CIDER nREPL middleware in
  Leiningen projects."
  (:require
   [cider.nrepl.middleware :as mw]
   [cider.nrepl.version :refer [version-string]]
   [leiningen.core.main :as lein]))

(def minimum-versions {:lein    "2.8.3"
                       :clojure "1.9.0"})

(defn valid-version? [kind version] (lein/version-satisfies? version (minimum-versions kind)))
(def valid-lein-version? (partial valid-version? :lein))
(def valid-clojure-version? (partial valid-version? :clojure))

;; Exists for the sole purpose of modifying the current project's metadata.
;; See https://github.com/technomancy/leiningen/blob/master/doc/PLUGINS.md#project-middleware
(defn middleware
  [{:keys [dependencies exclusions] :as project}]
  (let [lein-version-ok?    (valid-lein-version? (lein/leiningen-version))
        clojure-excluded?   (some #(= % 'org.clojure/clojure) exclusions)
        clojure-versions    (when-not clojure-excluded?
                              (->> dependencies
                                   (keep (fn [[id version & _]]
                                           (when (and (= id 'org.clojure/clojure)
                                                      ;; We do an additional check here to ensure
                                                      ;; a version is present. Some lein extensions
                                                      ;; such as lein modules or managed dependencies
                                                      ;; do not require versions in the dependency list
                                                      (string? version))
                                             version)))))
        clojure-version-ok? (cond clojure-excluded?
                                  ;; In this case the onus is on the user. A warning will be emitted
                                  ;; later, but we assume that the user will provide an appropriate
                                  ;; implementation.
                                  true

                                  (empty? clojure-versions)
                                  ;; Lein 2.8.3+ uses Clojure 1.8 by default, which would be OK.
                                  lein-version-ok?

                                  :else
                                  ;; There is a Clojure version depended on, it must check out.
                                  (some valid-clojure-version? clojure-versions))]

    (when-not lein-version-ok?
      (lein/warn "Warning: cider-nrepl requires Leiningen 2.8.3 or greater."))
    (when-not clojure-version-ok?
      (lein/warn "Warning: cider-nrepl requires Clojure 1.9 or greater."))
    (when clojure-excluded?
      (lein/warn "Warning: Clojure is excluded, assuming an appropriate fork (Clojure 1.9 or later) is provided."))
    (when-not (and lein-version-ok? clojure-version-ok?)
      (lein/warn "Warning: cider-nrepl will not be included in your project."))

    (cond-> project
      (and clojure-version-ok? lein-version-ok?)
      (-> (update-in [:dependencies]
                     (fnil into [])
                     [['cider/cider-nrepl version-string]])
          (update-in [:repl-options :nrepl-middleware]
                     (fnil into [])
                     mw/cider-middleware)))))
