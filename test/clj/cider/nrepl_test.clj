(ns cider.nrepl-test
  (:require
   [cider.nrepl :as sut]
   [clojure.test :refer [deftest is testing]]
   [nrepl.version]))

(deftest versions-sanity-check
  (is (let [v (System/getenv "CLOJURE_VERSION")]
        (println "Running on Clojure" (clojure-version))
        (or (nil? v) (.startsWith ^String (clojure-version) v))))
  (is (let [v (System/getenv "NREPL_VERSION")
            nver (:version-string nrepl.version/version)]
        (println "Running on nREPL" nver)
        (or (nil? v) (.startsWith ^String nver v)))))
