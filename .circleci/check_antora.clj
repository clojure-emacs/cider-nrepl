;; This script checks if the version declared in CHANGELOG matches that declared in antora.yml.
;; Which ensures that our generated docs will properly work.

(require '[clojure.string :as string])

(defn extract-readme-version [line]
  (when-let [[_ match] (re-find #"## (\d+\.\d+)\.\d+\s\(" line)]
    match))

(defn extract-antora-version [line]
  (when-let [[_ match] (re-find #"version: \"([0-9]+\.[0-9]+)\"" line)]
    match))

(def version-per-changelog
  (->> "CHANGELOG.md"
       slurp
       (string/split-lines)
       (some extract-readme-version)))

(def version-per-antora
  (->> "doc/antora.yml"
       slurp
       (string/split-lines)
       (some extract-antora-version)))

(assert (not (string/blank? version-per-changelog)))

(assert (not (string/blank? version-per-antora)))

(def ok?
  (= version-per-changelog version-per-antora))

(when-not ok?
  (printf "Version is not updated in doc/antora.yml. CHANGELOG.md version: %s Antora version: %s"
          version-per-changelog
          version-per-antora)
  (println))

(System/exit (if ok?
               0
               1))
