(ns cider.nrepl.plugin-test
  (:require
   [cider-nrepl.plugin :refer :all]
   [cider.nrepl.middleware :as mw]
   [cider.nrepl.version :refer [version-string]]
   [clojure.test :refer :all]))

(def expected-output
  {:dependencies [['org.clojure/clojure]
                  ['cider/cider-nrepl version-string]]
   :repl-options {:nrepl-middleware mw/cider-middleware}})

(deftest ^:cognitest-exclude version-checks
  (testing "undefined versions work"
    (is (= expected-output
           (middleware {:dependencies [['org.clojure/clojure]]}))))
  (testing "defined versions also work"
    (is (= (update-in expected-output [:dependencies 0] conj "1.10.3")
           (middleware {:dependencies [['org.clojure/clojure "1.10.3"]]})))))
