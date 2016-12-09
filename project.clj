(def VERSION "0.15.0-SNAPSHOT")

(defproject cider/cider-nrepl VERSION
  :description "nREPL middlewares for CIDER"
  :url "https://github.com/clojure-emacs/cider-nrepl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/tools.nrepl "0.2.12"]
                 [org.tcrawley/dynapath "0.2.5"]
                 ^:source-dep [mvxcvi/puget "1.0.1"]
                 ^:source-dep [fipp "0.6.6"]
                 ^:source-dep [compliment "0.3.2"]
                 ^:source-dep [cljs-tooling "0.2.0"]
                 ^:source-dep [cljfmt "0.5.6" :exclusions [org.clojure/clojurescript]]
                 ^:source-dep [org.clojure/java.classpath "0.2.3"]
                 ^:source-dep [org.clojure/tools.namespace "0.2.11"]
                 ^:source-dep [org.clojure/tools.trace "0.7.9"]
                 ^:source-dep [org.clojure/tools.reader "0.10.0"]]
  :plugins [[thomasa/mranderson "0.4.7"]]
  :exclusions [org.clojure/clojure]

  :filespecs [{:type :bytes :path "cider/cider-nrepl/project.clj" :bytes ~(slurp "project.clj")}]

  :test-paths ["test/common"] ;; See `test-clj` and `test-cljs` profiles below.

  :profiles {:provided {:dependencies [[org.clojure/clojure "1.7.0"]]}

             :dev {:repl-options {:nrepl-middleware [cider.nrepl.middleware.apropos/wrap-apropos
                                                     cider.nrepl.middleware.classpath/wrap-classpath
                                                     cider.nrepl.middleware.complete/wrap-complete
                                                     cider.nrepl.middleware.debug/wrap-debug
                                                     cider.nrepl.middleware.enlighten/wrap-enlighten
                                                     cider.nrepl.middleware.format/wrap-format
                                                     cider.nrepl.middleware.info/wrap-info
                                                     cider.nrepl.middleware.inspect/wrap-inspect
                                                     cider.nrepl.middleware.macroexpand/wrap-macroexpand
                                                     cider.nrepl.middleware.ns/wrap-ns
                                                     cider.nrepl.middleware.out/wrap-out
                                                     cider.nrepl.middleware.pprint/wrap-pprint
                                                     cider.nrepl.middleware.pprint/wrap-pprint-fn
                                                     cider.nrepl.middleware.refresh/wrap-refresh
                                                     cider.nrepl.middleware.resource/wrap-resource
                                                     cider.nrepl.middleware.stacktrace/wrap-stacktrace
                                                     cider.nrepl.middleware.test/wrap-test
                                                     cider.nrepl.middleware.trace/wrap-trace
                                                     cider.nrepl.middleware.track-state/wrap-tracker
                                                     cider.nrepl.middleware.undef/wrap-undef
                                                     cider.nrepl.middleware.version/wrap-version]}
                   :dependencies [[org.clojure/tools.nrepl "0.2.12"]
                                  ;; For developing the Leiningen plugin.
                                  [leiningen-core "2.6.1"]]}

             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0-alpha12"]]
                   :test-paths ["test/spec"]}
             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.8.0-master-SNAPSHOT"]]}

             :test-clj {:test-paths ["test/clj"]
                        :java-source-paths ["test/java"]
                        :resource-paths ["test/resources"]}
             :test-cljs {:test-paths ["test/cljs"]
                         :dependencies [[com.cemerick/piggieback "0.2.1"]
                                        [org.clojure/clojurescript "1.7.189"]]}

             :cloverage {:plugins [[lein-cloverage "1.0.7-SNAPSHOT"]]}

             :cljfmt {:plugins [[lein-cljfmt "0.4.1"]]
                      :cljfmt {:indents {as-> [[:inner 0]]
                                         with-debug-bindings [[:inner 0]]
                                         merge-meta [[:inner 0]]}}}

             :eastwood {:plugins [[jonase/eastwood "0.2.3"]]
                        :eastwood {:config-files ["eastwood.clj"]}}})
