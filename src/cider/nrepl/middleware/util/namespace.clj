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

(defn inlined-dependency?
  "Returns true if the namespace matches one of our, or eastwood's,
  inlined dependencies."
  [namespace]
  (let [ns-name (str (ns-name namespace))]
    (or
     ;; rewritten by mranderson
     (.startsWith ns-name "deps.")
     (.startsWith ns-name "mranderson")
     (.startsWith ns-name "cider.inlined-deps")
     ;; rewritten by dolly
     (.startsWith ns-name "eastwood.copieddeps"))))

(defn loaded-namespaces
  "Return all loaded namespaces, except those coming from inlined dependencies."
  []
  (->> (all-ns)
       (remove inlined-dependency?)
       (map ns-name)
       (map name)
       (sort)))

(defn loaded-project-namespaces
  "Return all loaded namespaces defined in the current project."
  []
  (->> (project-namespaces)
       (filter (set (map ns-name (all-ns))))
       sort))

(defn load-project-namespaces
  "Require and return all namespaces validly defined in the current project."
  []
  (->> (project-namespaces)
       (map ensure-namespace)
       (filter identity)
       sort))
