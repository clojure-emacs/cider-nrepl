(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/smoketest-0.1.0-standalone.jar")

(defn uber
  "Build a standalone uberjar bundling the just-built cider-nrepl, so the smoke
  test can confirm the artifact loads and works outside the project's own build.

  Pass the cider-nrepl version to test:
    clojure -T:build uber :cider-nrepl-version '\"x.y.z\"'"
  [{:keys [cider-nrepl-version]}]
  (assert cider-nrepl-version ":cider-nrepl-version is required")
  (let [basis (b/create-basis
               {:extra {:deps {'cider/cider-nrepl {:mvn/version cider-nrepl-version}}}})]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/compile-clj {:basis basis :class-dir class-dir :ns-compile '[smoketest.core]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      'smoketest.core})))
