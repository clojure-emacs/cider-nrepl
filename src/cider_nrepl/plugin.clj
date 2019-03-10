(ns cider-nrepl.plugin
  "Provides a simple way to setup the CIDER nREPL middleware in
  Leiningen projects."
  (:require
   [cider.nrepl.version :refer [version-string]]
   [clojure.java.io :as io]
   [leiningen.core.main :as lein]))

(def minimum-versions {:lein    "2.8.2"
                       :clojure "1.8.0"})

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
                                           (when (= id 'org.clojure/clojure)
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
                     ;; TODO: it would be better to avoid this duplication, but
                     ;; we can't require the `cider.nrepl` namespace because it
                     ;; requires `nrepl.*` namespaces which might conflict with
                     ;; Leiningen. Consider adding another namespace that
                     ;; contains the below definition (as data only, not
                     ;; requiring `cider.nrepl`).
                     '[cider.nrepl/wrap-apropos
                       cider.nrepl/wrap-classpath
                       cider.nrepl/wrap-complete
                       cider.nrepl/wrap-debug
                       cider.nrepl/wrap-enlighten
                       cider.nrepl/wrap-format
                       cider.nrepl/wrap-info
                       cider.nrepl/wrap-inspect
                       cider.nrepl/wrap-macroexpand
                       cider.nrepl/wrap-slurp
                       cider.nrepl/wrap-ns
                       cider.nrepl/wrap-out
                       cider.nrepl/wrap-content-type
                       cider.nrepl/wrap-slurp
                       cider.nrepl/wrap-profile
                       cider.nrepl/wrap-refresh
                       cider.nrepl/wrap-resource
                       cider.nrepl/wrap-spec
                       cider.nrepl/wrap-stacktrace
                       cider.nrepl/wrap-test
                       cider.nrepl/wrap-trace
                       cider.nrepl/wrap-tracker
                       cider.nrepl/wrap-undef
                       cider.nrepl/wrap-version])))))
