(ns cider.nrepl.middleware.util.namespace
  "Utilities for resolving and loading namespaces"
  (:require [clojure.java.classpath :as cp]
            [clojure.tools.namespace.find :as ns-find]))


;;; Namespace Loading

(defn ensure-namespace
  "Require `ns` (no-op if already loaded). Return the symbol if successful,
  and `nil` if this fails."
  [ns]
  (try (doto (symbol ns) require)
       (catch Exception _)))


;;; Project Namespaces
;; These methods search project sources on the classpath. Non-classpath source
;; files, documentation code, etc within the project directory are ignored.

(def project-root
  (str (System/getProperty "user.dir")
       (System/getProperty "file.separator")))

(defn project-namespaces
  "Find all namespaces defined in source paths within the current project."
  []
  (->> (cp/classpath-directories)
       (filter #(re-find (re-pattern (str "^" project-root))
                         (str %)))
       (mapcat ns-find/find-namespaces-in-dir)))

(defn loaded-project-namespaces
  "Return all loaded namespaces defined in the current project."
  []
  (sort (filter (set (map ns-name (all-ns)))
                (project-namespaces))))

(defn load-project-namespaces
  "Require and return all namespaces validly defined in the current project."
  []
  (sort (filter identity
                (map ensure-namespace
                     (project-namespaces)))))
