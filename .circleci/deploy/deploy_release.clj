(ns deploy-release
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]))

(def release-marker "v")

(defn make-version [tag]
  (str/replace-first tag release-marker ""))

(defn log-result [m]
  (println m)
  m)

(defn -main [& _]
  (let [tag (System/getenv "CIRCLE_TAG")]
    (if-not tag
      (do
        (println "No CIRCLE_TAG found.")
        (System/exit 1))
      (if-not (re-find (re-pattern release-marker) tag)
        (do
          (println (format "The `%s` marker was not found in %s." release-marker tag))
          (System/exit 1))
        (let [version (make-version tag)
              version-file (io/file "resources" "cider" "nrepl" "version.edn")]
          (assert (.exists version-file))
          (spit version-file (pr-str version))
          (apply println "Executing" *command-line-args*)
          (->> [:env (-> {}
                         (into (System/getenv))
                         (assoc "PROJECT_VERSION" version)
                         (dissoc "CLASSPATH"))]
               (into (vec *command-line-args*))
               (apply sh)
               log-result
               :exit
               (System/exit)))))))
