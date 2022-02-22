(def fipp-version "0.6.25")

(defproject cider/cider-nrepl (or (not-empty (System/getenv "PROJECT_VERSION"))
                                  "0.0.0")
  :description "A collection of nREPL middleware designed to enhance Clojure editors."
  :url "https://github.com/clojure-emacs/cider-nrepl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/clojure-emacs/cider-nrepl"}
  :dependencies [[nrepl "0.9.0"]
                 ^:inline-dep [cider/orchard "0.9.2" :exclusions [com.google.code.findbugs/jsr305 com.google.errorprone/error_prone_annotations]]
                 ^:inline-dep [thunknyc/profile "0.5.2"]
                 ^:inline-dep [mvxcvi/puget "1.3.1"]
                 ^:inline-dep [fipp ~fipp-version] ; can be removed in unresolved-tree mode
                 ^:inline-dep [compliment "0.3.12"]
                 ^:inline-dep [org.rksm/suitable "0.4.1" :exclusions [org.clojure/clojurescript]]
                 ^:inline-dep [cljfmt "0.8.0" :exclusions [org.clojure/clojurescript]]
                 ^:inline-dep [org.clojure/tools.namespace "1.2.0"]
                 ^:inline-dep [org.clojure/tools.trace "0.7.11"]
                 ^:inline-dep [org.clojure/tools.reader "1.3.6"]]
  :exclusions [org.clojure/clojure] ; see Clojure version matrix in profiles below

  :pedantic? ~(if (System/getenv "CI")
                :abort
                ;; :pedantic? can be problematic for certain local dev workflows:
                false)

  :plugins [[thomasa/mranderson "0.5.4-SNAPSHOT"]]
  :mranderson {:project-prefix "cider.nrepl.inlined-deps"
               :overrides       {[mvxcvi/puget fipp] [fipp ~fipp-version]} ; only takes effect in unresolved-tree mode
               :expositions     [[mvxcvi/puget fipp]] ; only takes effect unresolved-tree mode
               :unresolved-tree false}

  :filespecs [{:type :bytes :path "cider/cider-nrepl/project.clj" :bytes ~(slurp "project.clj")}]

  :source-paths ["src"]
  :resource-paths ["resources"]
  :test-paths ["test/clj" "test/cljs" "test/common"]

  :test-selectors {:default (fn [test-meta]
                              (let [parse-version (fn [v] (mapv #(Integer/parseInt (re-find #"\d+" %)) (clojure.string/split v #"\.")))
                                    clojure-version (parse-version (clojure-version))]
                                (and (if-let [min-version (:min-clj-version test-meta)]
                                       (>= (compare clojure-version (parse-version min-version)) 0)
                                       true)
                                     (if-let [max-version (:max-clj-version test-meta)]
                                       (>= (compare (parse-version max-version) clojure-version) 0)
                                       true))))
                   :debugger :debugger}

  :aliases {"bump-version" ["change" "version" "leiningen.release/bump-version"]
            "mranderson"   ["with-profile" "+plugin.mranderson/config"]
            "docs" ["with-profile" "+maint" "run" "-m" "cider.nrepl.impl.docs" "--file"
                    ~(clojure.java.io/as-relative-path
                      (clojure.java.io/file "doc" "modules" "ROOT" "pages" "nrepl-api" "ops.adoc"))]}

  :release-tasks [["vcs" "assert-committed"]
                  ["bump-version" "release"]
                  ["vcs" "commit" "Release %s"]
                  ["vcs" "tag" "v" "--no-sign"]
                  ["bump-version"]
                  ["vcs" "commit" "Begin %s"]]

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :profiles {:provided {:dependencies [[org.clojure/clojure "1.10.3"]
                                       [org.clojure/clojurescript "1.11.4" :scope "provided"]
                                       [com.cognitect/transit-clj "1.0.324"]
                                       [com.fasterxml.jackson.core/jackson-core "2.13.1"]
                                       [commons-codec "1.15"]
                                       [com.cognitect/transit-java "1.0.343"]
                                       [com.google.errorprone/error_prone_annotations "2.11.0"]
                                       [com.google.code.findbugs/jsr305 "3.0.2"]]
                        :test-paths ["test/spec"]}

             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [org.clojure/clojurescript "1.10.520" :scope "provided"]
                                  [javax.xml.bind/jaxb-api "2.3.1" :scope "provided"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [org.clojure/clojurescript "1.10.520" :scope "provided"]
                                  [javax.xml.bind/jaxb-api "2.3.1" :scope "provided"]]
                   ;; TODO: Merge the tests in this dir in to test/clj once we
                   ;; drop support for Clojure 1.8
                   :test-paths ["test/spec"]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.1"]
                                   [org.clojure/clojurescript "1.10.520" :scope "provided"]]
                    :test-paths ["test/spec"]}
             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.11.0-master-SNAPSHOT"]
                                     [org.clojure/clojurescript "1.10.520" :scope "provided"]]}

             :maint {:source-paths ["src" "maint"]
                     :dependencies [[org.clojure/tools.cli "1.0.194"]]}

             :test {:global-vars {*assert* true}
                    :source-paths ["test/src"]
                    :java-source-paths ["test/java"]
                    :resource-paths ["test/resources"]
                    :dependencies [[boot/base "2.8.3"]
                                   [boot/core "2.8.3"]
                                   [org.apache.httpcomponents/httpclient "4.5.9" :exclusions [commons-logging]]
                                   [leiningen-core "2.9.6" :exclusions [org.clojure/clojure
                                                                        commons-codec
                                                                        com.google.code.findbugs/jsr305]]
                                   [cider/piggieback "0.5.3"]]}

             ;; Need ^:repl because of: https://github.com/technomancy/leiningen/issues/2132
             :repl ^:repl [:test
                           {:repl-options {:nrepl-middleware [cider.nrepl/wrap-apropos
                                                              cider.nrepl/wrap-classpath
                                                              cider.nrepl/wrap-complete
                                                              cider.nrepl/wrap-content-type
                                                              cider.nrepl/wrap-debug
                                                              cider.nrepl/wrap-enlighten
                                                              cider.nrepl/wrap-format
                                                              cider.nrepl/wrap-info
                                                              cider.nrepl/wrap-inspect
                                                              cider.nrepl/wrap-macroexpand
                                                              cider.nrepl/wrap-ns
                                                              cider.nrepl/wrap-out
                                                              cider.nrepl/wrap-profile
                                                              cider.nrepl/wrap-refresh
                                                              cider.nrepl/wrap-resource
                                                              cider.nrepl/wrap-slurp
                                                              cider.nrepl/wrap-spec
                                                              cider.nrepl/wrap-stacktrace
                                                              cider.nrepl/wrap-test
                                                              cider.nrepl/wrap-trace
                                                              cider.nrepl/wrap-tracker
                                                              cider.nrepl/wrap-undef
                                                              cider.nrepl/wrap-version
                                                              cider.nrepl/wrap-xref]}}]

             :cljfmt [:test
                      {:plugins [[lein-cljfmt "0.8.0"]]
                       :cljfmt {:indents {as-> [[:inner 0]]
                                          delay [[:inner 0]]
                                          with-debug-bindings [[:inner 0]]
                                          merge-meta [[:inner 0]]
                                          try-if-let [[:block 1]]}}}]

             :clj-kondo [:test
                         {:dependencies [[clj-kondo "2021.12.01"]]}]

             :eastwood [:test
                        {:plugins [[jonase/eastwood "1.2.2"]]
                         :eastwood {:config-files ["eastwood.clj"]
                                    :exclude-namespaces [cider.nrepl.middleware.test-filter-tests]
                                    :ignored-faults {:unused-ret-vals-in-try {cider.nrepl.middleware.profile-test [{:line 25}]}
                                                     :suspicious-test {cider.nrepl.middleware.profile-test [{:line 25}]}}}}]

             :deploy {:source-paths [".circleci/deploy"]}})
