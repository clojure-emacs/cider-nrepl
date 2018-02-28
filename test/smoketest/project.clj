(defproject smoketest "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/tools.nrepl "0.2.13"]
                 [cider/cider-nrepl "0.17.0-SNAPSHOT"]]
  :exclusions [org.clojure/clojure]
  :profiles {:1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.10.0-master-SNAPSHOT"]]}
             :uberjar {:aot :all}}
  :main smoketest.core)
