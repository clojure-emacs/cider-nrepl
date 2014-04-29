(defproject cider/cider-nrepl "0.7.0-SNAPSHOT"
  :description "nREPL middleware for CIDER"
  :url "https://github.com/clojure-emacs/cider-nrepl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [compliment "0.0.3"]
                 [cljs-tooling "0.1.2"]
                 [org.tcrawley/dynapath "0.2.3"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [org.clojure/java.classpath "0.2.0"]
                 [org.clojure/tools.namespace "0.2.3"]]

  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0-master-SNAPSHOT"]]}
             :dev {:repl-options {:nrepl-middleware [cider.nrepl.middleware.classpath/wrap-classpath
                                                     cider.nrepl.middleware.complete/wrap-complete
                                                     cider.nrepl.middleware.info/wrap-info
                                                     cider.nrepl.middleware.inspect/wrap-inspect
                                                     cider.nrepl.middleware.stacktrace/wrap-stacktrace
                                                     ]}

                   ;; The "sources" jar should be the same version as Clojure,
                   ;; but bad sources jars were deployed to the Maven Central
                   ;; "releases" repo, so for the moment, use sources from
                   ;; "snapshot" builds to run tests.
                   ;; See http://dev.clojure.org/jira/browse/CLJ-1161.
                   :repositories [["snapshots"
                                   "http://oss.sonatype.org/content/repositories/snapshots"]]
                   :dependencies [[org.clojure/clojure "1.5.2-SNAPSHOT"
                                   :classifier "sources"]]

                   ;; Wait til 1.5 comes out for a fix to cljs dep
                   ;; :plugins [[com.cemerick/austin "0.1.5"]]
                   }}
  )
