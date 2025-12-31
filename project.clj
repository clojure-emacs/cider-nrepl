(def dev-test-common-profile
  {:dependencies '[[org.clojure/clojurescript "1.11.60" :scope "provided"]
                   ;; 1.3.7 and 1.4.7 are working, but we need 1.3.7 for JDK8
                   [ch.qos.logback/logback-classic "1.3.7"]
                   [mvxcvi/puget "1.3.4" :exclusions [org.clojure/clojure]]
                   [fipp "0.6.26"]
                   [org.clojure/test.check "1.1.1"]
                   [cider/piggieback "0.6.0"]
                   [nubank/matcher-combinators "3.9.1"]]
   :source-paths ["test/src"]
   :global-vars {'*assert* true}
   :java-source-paths ["test/java"]
   :jvm-opts ["-Djava.util.logging.config.file=test/resources/logging.properties"]
   :resource-paths ["test/java" "test/resources"]})

(defproject cider/cider-nrepl (or (not-empty (System/getenv "PROJECT_VERSION"))
                                  "0.0.0")
  :description "A collection of nREPL middleware designed to enhance Clojure editors."
  :url "https://github.com/clojure-emacs/cider-nrepl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/clojure-emacs/cider-nrepl"}
  :dependencies
  ~(cond-> '[[cider/orchard "0.37.1" :exclusions [org.clojure/clojure]]
             ^:inline-dep [compliment "0.7.1"]
             ^:inline-dep [org.rksm/suitable "0.6.2" :exclusions [org.clojure/clojure
                                                                  org.clojure/clojurescript]]
             ^:inline-dep [cljfmt "0.9.2" :exclusions [org.clojure/clojurescript
                                                       org.clojure/tools.cli]]
             ^:inline-dep [org.clojure/tools.namespace "1.5.0" :exclusions [org.clojure/clojurescript
                                                                            org.clojure/tools.cli]]
             ^:inline-dep [io.github.tonsky/clj-reload "1.0.0" :exclusions [org.clojure/clojure]]
             ^:inline-dep [org.clojure/tools.reader "1.4.1"]
             [mx.cider/logjam "0.3.0" :exclusions [org.clojure/clojure]]]
     ;; This is the only working way to include nREPL into published jar and
     ;; still be able to test different nREPL versions.
     (System/getenv "CIDER_RELEASE") (conj '[nrepl/nrepl "1.5.2"]))

  :pedantic? ~(if (and (System/getenv "CI")
                       (not (System/getenv "CIDER_NO_PEDANTIC")))
                :abort
                ;; :pedantic? can be problematic for certain local dev workflows:
                false)

  ;; mranderson cannot be put in a profile (as the other plugins),
  ;; so we conditionally disable it, because otherwise clj-kondo cannot run.
  :plugins ~(if (System/getenv "CIDER_NO_MRANDERSON")
              []
              '[[thomasa/mranderson "0.5.4-SNAPSHOT"]])

  :mranderson {:project-prefix "cider.nrepl.inlined.deps"
               :unresolved-tree false}

  :filespecs [{:type :bytes :path "cider/cider-nrepl/project.clj" :bytes ~(slurp "project.clj")}]

  :source-paths ["src"]
  :java-source-paths ["src"]
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

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :profiles {:provided {:dependencies [[org.clojure/clojure "1.12.4"]
                                       [nrepl/nrepl "1.5.2"]]}

             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]
                                   [org.clojure/clojurescript "1.10.520" :scope "provided"]]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.4"]
                                   [org.clojure/clojurescript "1.11.60" :scope "provided"]]}
             :1.12 {:dependencies [[org.clojure/clojure "1.12.4"]
                                   [org.clojure/clojurescript "1.12.42" :scope "provided"]]}

             :nrepl-1.0 {:dependencies [[nrepl/nrepl "1.0.0"]]}
             :nrepl-1.3 {:dependencies [[nrepl/nrepl "1.3.0"]]}
             :nrepl-1.4 {:dependencies [[nrepl/nrepl "1.4.0"]]}
             :nrepl-1.5 {:dependencies [[nrepl/nrepl "1.5.2"]]}

             :maint {:source-paths ["src" "maint"]
                     :dependencies [[org.clojure/tools.cli "1.2.245"]]}

             :dev ~dev-test-common-profile

             :test ~(-> dev-test-common-profile
                        (update :dependencies conj '[leiningen-core "2.11.2"
                                                     :exclusions [org.clojure/clojure
                                                                  commons-codec
                                                                  com.google.code.findbugs/jsr305]]))

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
                                                              cider.nrepl/wrap-reload
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
                      {:plugins [[dev.weavejester/lein-cljfmt "0.13.1"]]
                       :cljfmt {:extra-indents {timing [[:inner 0]]
                                                with-debug-bindings [[:inner 0]]
                                                merge-meta [[:inner 0]]
                                                try-if-let [[:block 1]]}}}]

             :clj-kondo {:plugins [[com.github.clj-kondo/lein-clj-kondo "2025.04.07"]]}

             :eastwood [:test
                        {:plugins [[jonase/eastwood "1.4.3"]]
                         :eastwood {:config-files ["eastwood.clj"]
                                    :ignored-faults {:unused-ret-vals {orchard.java {:line 84}
                                                                       cider.nrepl.middleware.util.instrument {:line 396}}}
                                    :exclude-namespaces [cider.nrepl.middleware.debug-test
                                                         cider.nrepl.middleware.test-filter-tests]}}]})
