(defproject smoketest "0.1.0-SNAPSHOT"
  :dependencies [[nrepl "1.0.0"]
                 [cider/cider-nrepl "0.28.6"]]
  :exclusions [org.clojure/clojure]
  :profiles {:1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.1"]]}
             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.11.0-master-SNAPSHOT"]]}
             :uberjar {:aot :all}}
  :main smoketest.core)
