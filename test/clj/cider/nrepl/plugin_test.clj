(ns cider.nrepl.plugin-test
  (:require [cider-nrepl.plugin :refer :all]
            [cider.nrepl.version :refer [version-string]]
            [clojure.test :refer :all]))

(def expected-output
  {:dependencies
   [['org.clojure/clojure]
    ['cider/cider-nrepl version-string]],
   :repl-options
   {:nrepl-middleware
    '[cider.nrepl/wrap-apropos
      cider.nrepl/wrap-classpath
      cider.nrepl/wrap-clojuredocs
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
      cider.nrepl/wrap-slurp
      cider.nrepl/wrap-profile
      cider.nrepl/wrap-refresh
      cider.nrepl/wrap-resource
      cider.nrepl/wrap-spec
      cider.nrepl/wrap-stacktrace
      cider.nrepl/wrap-test
      cider.nrepl/wrap-trace
      cider.nrepl/wrap-tracker
      cider.nrepl/wrap-undef
      cider.nrepl/wrap-version
      cider.nrepl/wrap-xref]}})

(deftest version-checks
  (testing "undefined versions work"
    (is (= expected-output
           (middleware {:dependencies [['org.clojure/clojure]]}))))
  (testing "defined versions also work"
    (is (= (update-in expected-output [:dependencies 0] conj "1.10.0")
           (middleware {:dependencies [['org.clojure/clojure "1.10.0"]]})))))
