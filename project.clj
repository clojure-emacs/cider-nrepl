(def VERSION "0.8.0")

(def VERSION-FORM `(do (require 'cider-nrepl.plugin)
                       (alter-var-root #'cider-nrepl.plugin/version
                                       (constantly (constantly ~VERSION)))))

(defproject cider/cider-nrepl VERSION
  :description "nREPL middlewares for CIDER"
  :url "https://github.com/clojure-emacs/cider-nrepl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[compliment "0.2.0"]
                 [cljs-tooling "0.1.3"]
                 [org.tcrawley/dynapath "0.2.3"]
                 [org.clojure/tools.nrepl "0.2.6"]
                 [org.clojure/java.classpath "0.2.0"]
                 [org.clojure/tools.namespace "0.2.5"]
                 [org.clojure/tools.trace "0.7.8"]]
  :exclusions [org.clojure/clojure]
  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0-master-SNAPSHOT"]]}
             :dev {:repl-options {:nrepl-middleware [cider.nrepl.middleware.apropos/wrap-apropos
                                                     cider.nrepl.middleware.classpath/wrap-classpath
                                                     cider.nrepl.middleware.complete/wrap-complete
                                                     cider.nrepl.middleware.info/wrap-info
                                                     cider.nrepl.middleware.inspect/wrap-inspect
                                                     cider.nrepl.middleware.macroexpand/wrap-macroexpand
                                                     cider.nrepl.middleware.ns/wrap-ns
                                                     cider.nrepl.middleware.resource/wrap-resource
                                                     cider.nrepl.middleware.stacktrace/wrap-stacktrace
                                                     cider.nrepl.middleware.test/wrap-test
                                                     cider.nrepl.middleware.trace/wrap-trace
                                                     cider.nrepl.middleware.undef/wrap-undef
                                                     ]}
                   ;; The "sources" jar should be the same version as Clojure,
                   ;; but bad sources jars were deployed to the Maven Central
                   ;; "releases" repo, so for the moment, use sources from
                   ;; "snapshot" builds to run tests.
                   ;; See http://dev.clojure.org/jira/browse/CLJ-1161.
                   :repositories [["snapshots"
                                   "https://oss.sonatype.org/content/repositories/snapshots"]]
                   :dependencies [[org.clojure/clojure "1.5.2-SNAPSHOT"
                                   :classifier "sources"]
                                  [org.clojure/clojure "1.5.1"
                                   :classifier "javadoc"]]
                   :injections [~VERSION-FORM]
                   :plugins [[jonase/eastwood "0.1.4"]
                             ;; Wait til 1.5 comes out for a fix to cljs dep
                             ;; [com.cemerick/austin "0.1.5"]
                             ]}
             :test {:resource-paths ["test/resources"]}
             :provided {:dependencies [[org.clojure/clojure "1.5.1"]]}}
  )
