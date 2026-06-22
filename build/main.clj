(ns build.main
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
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

     ;; Build section. The pom basis intentionally excludes the inlined deps:
     ;; only the plain :deps end up declared as dependencies. ClojureScript is
     ;; added here (provided, so non-transitive) purely so it shows up in the
     ;; pom like the historical Leiningen build; it is not on the base classpath
     ;; (it would make the macroexpand middleware load Closure on old JDKs) and
     ;; the jar is assembled from explicit dirs, so it is not bundled either.
     :basis (b/create-basis
             {:extra {:deps {'org.clojure/clojurescript {:mvn/version "1.12.145"
                                                         :mvn/scope "provided"}}}})
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
