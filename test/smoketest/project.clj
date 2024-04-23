(defproject smoketest "0.1.0-SNAPSHOT"
  :dependencies [[nrepl "1.0.0"]
                 [cider/cider-nrepl "RELEASE"]]
  :profiles {:1.10 {:dependencies [[org.clojure/clojure "1.10.1"]]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.1"]]}
             :master {:repositories [["snapshots" "https://oss.sonatype.org/content/repositories/snapshots"]]
                      :dependencies [[org.clojure/clojure "1.12.0-master-SNAPSHOT"]]}
             :uberjar {:aot :all}}
  :main smoketest.core)
