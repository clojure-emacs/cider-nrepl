(ns build.main
  (:refer-clojure :exclude [test])
  (:require [build.download-jdk-sources :as download-src]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.tools.build.tasks.write-pom]
            [deps-deploy.deps-deploy :as dd]
            [mranderson.core :as mranderson]))

;; Dependencies that are source-shaded into the jar so they can't clash with the
;; versions in the user's project. Keep in sync with the :inlined-deps alias in
;; deps.edn (which puts the same libs on the classpath for non-inlined runs).
(def inlined-deps
  '[[dev.weavejester/cljfmt "0.16.4" :exclusions [org.clojure/clojure
                                                  org.clojure/clojurescript
                                                  org.clojure/tools.cli]]
    [org.clojure/tools.namespace "1.5.1"]
    [org.clojure/tools.reader "1.6.0"]])

(def project-prefix "cider.nrepl.inlined.deps")

(defn default-opts [{:keys [version] :or {version "99.99"}}]
  (let [lib 'cider/cider-nrepl
        url "https://github.com/clojure-emacs/cider-nrepl"
        target "target"]
    {;; Pom section
     :lib lib
     :version version
     :scm {:url url, :tag version}
     :pom-data [[:description "A collection of nREPL middleware designed to enhance Clojure editors."]
                [:url url]
                [:licenses
                 [:license
                  [:name "Eclipse Public License"]
                  [:url "http://www.eclipse.org/legal/epl-v10.html"]]]]

     ;; Build section
     :basis (b/create-basis)
     :target target
     :class-dir (str target "/classes")
     :srcdeps (str target "/srcdeps")
     :jar-file (some->> version (format "%s/%s-%s.jar" target (name lib)))}))

(defmacro defcmd [name args & body]
  (assert (= (count args) 1))
  `(defn ~name [~'opts]
     (let [~'opts (cond-> ~'opts
                    (:version ~'opts) (update :version str))
           ~(first args) (merge (default-opts ~'opts) ~'opts)]
       ~@body)))

(defn log [fmt & args] (println (apply format fmt args)))

(defcmd clean [opts] (b/delete {:path (:target opts)}))

(defcmd javac [{:keys [with-tests] :as opts}]
  (b/javac (assoc opts
                  :src-dirs (if with-tests ["src" "test/java"] ["src"])
                  :javac-opts ["-source" "8" "-target" "8"])))

;; Hack to propagate scope into pom (so provided deps stay provided).
(alter-var-root
 #'clojure.tools.build.tasks.write-pom/to-dep
 (fn [f]
   (fn [[_ {:keys [mvn/scope]} :as arg]]
     (let [res (f arg)
           alias (some-> res first namespace)]
       (cond-> res
         (and alias scope) (conj [(keyword alias "scope") scope]))))))

(defcmd inline-deps [{:keys [srcdeps version] :as opts}]
  (clean opts)
  (log "Inlining dependencies into %s..." srcdeps)
  (mranderson/inline-deps
   {:project-prefix project-prefix
    :source-paths   ["src"]
    :target-path    (:target opts)
    ;; pname/pversion drive the prefix used when repackaging bundled Java
    ;; classes (e.g. cljfmt's diffutils), so keep them cider-specific to avoid
    ;; clashing with another mranderson user's defaults.
    :pname          "cider-nrepl"
    :pversion       version
    :dependencies   inlined-deps})
  ;; cljfmt.main depends on tools.cli, which we deliberately don't ship; drop it
  ;; if a cljfmt version happens to bundle it.
  (doseq [f (->> (io/file srcdeps) file-seq
                 (filter #(= "main.clj" (.getName ^java.io.File %)))
                 (filter #(re-find #"/cljfmt/" (str %))))]
    (b/delete {:path (str f)})))

(defcmd jar [{:keys [class-dir srcdeps version] :as opts}]
  (assert version)
  (inline-deps opts)
  (javac opts)
  (b/write-pom opts)
  (log "Building %s..." (:jar-file opts))
  (b/copy-dir {:src-dirs   [srcdeps "resources" class-dir]
               :target-dir class-dir
               :include    "**"})
  ;; Stamp the version resource into the jar without touching the source tree.
  (spit (io/file class-dir "cider" "nrepl" "version.edn") (pr-str version))
  (b/jar opts))

(defcmd deploy [{:keys [version jar-file] :as opts}]
  (assert (some->> version (re-matches #"\d+\.\d+\.\d+.*")) (str version))
  (jar opts)
  (log "Deploying %s to Clojars..." version)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path opts)}))

(defcmd install [{:keys [version] :as opts}]
  (jar opts)
  (log "Installing %s to local Maven repository..." version)
  (b/install opts))

(defcmd download-jdk-src [opts]
  (let [base-file (io/file "base-jdk-src.zip")
        java-home (System/getenv "JAVA_HOME")
        dest-file (io/file java-home "lib" "src.zip")]
    (download-src/download-and-repackage-sources "base-jdk-src.zip")
    (when (.exists base-file)
      (b/copy-file {:src "base-jdk-src.zip", :target (str dest-file)}))))

;;; Release preparation

(def ^:private changelog-file "CHANGELOG.md")
(def ^:private usage-file "doc/modules/ROOT/pages/usage.adoc")
(def ^:private ops-file "doc/modules/ROOT/pages/nrepl-api/ops.adoc")
(def ^:private unreleased-marker "## master (unreleased)\n")

(defn- roll-changelog
  "Stamp the unreleased section with `version` and `date`, leaving an empty
  unreleased section on top for the next cycle."
  [contents version date]
  (when-not (str/includes? contents unreleased-marker)
    (throw (ex-info (str "Couldn't find the '" (str/trim unreleased-marker)
                         "' marker in " changelog-file)
                    {})))
  (str/replace-first contents unreleased-marker
                     (str unreleased-marker "\n## " version " (" date ")\n")))

(defn- bump-usage-version
  "Point the cider-nrepl coordinates in the usage docs at `version`, in both the
  Leiningen (`[cider/cider-nrepl \"x\"]`) and deps.edn (`{:mvn/version \"x\"}`)
  forms. Other coordinates (Clojure, piggieback, ...) are left untouched."
  [contents version]
  (-> contents
      (str/replace #"cider/cider-nrepl \"[^\"]+\""
                   (str "cider/cider-nrepl \"" version "\""))
      (str/replace #"cider/cider-nrepl \{:mvn/version \"[^\"]+\""
                   (str "cider/cider-nrepl {:mvn/version \"" version "\""))))

(defn release
  "Prepare a release of the given version: roll the CHANGELOG's unreleased
  section over to `version` dated today, bump the cider-nrepl version in the
  usage docs, commit the result and create the annotated `vX.Y.Z` tag.

  Pushing the tag is what triggers the Clojars deploy on CI, so that step is
  deliberately left to you.

    clojure -T:build release :version '\"0.62.0\"'"
  [{:keys [version]}]
  (let [version (str version)
        tag (str "v" version)]
    (assert (re-matches #"\d+\.\d+\.\d+" version)
            (str "Expected a release version like 0.62.0, got: " version))
    (let [date (str (java.time.LocalDate/now))]
      (spit changelog-file (roll-changelog (slurp changelog-file) version date))
      (spit usage-file (bump-usage-version (slurp usage-file) version)))
    ;; Commit just these paths so any unrelated staged changes are left alone.
    ;; The ops docs are regenerated by the `release` make target right before
    ;; this runs; including the file here picks up the refresh when it changed.
    (b/git-process {:git-args ["commit" "-m" version changelog-file usage-file ops-file]})
    (b/git-process {:git-args ["tag" "-a" tag "-m" version]})
    (log "Prepared release %s." version)
    (log "Review the commit, then push to deploy: git push origin HEAD %s" tag)))
