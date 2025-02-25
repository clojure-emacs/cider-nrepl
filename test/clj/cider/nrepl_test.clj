(ns cider.nrepl-test
  (:require
   [cider.nrepl :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest clojure-version-sanity-check
  (is (let [v (System/getenv "CLOJURE_VERSION")]
        (println "Running on Clojure" (clojure-version))
        (or (nil? v) (.startsWith ^String (clojure-version) v)))))
