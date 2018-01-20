(ns cider.nrepl.middleware.util.classloader
  (:require [orchard.java :as java]
            [orchard.misc :refer [boot-fake-classpath boot-project?]]
            [clojure.string :as str]))

(defn boot-class-loader
  "Creates a class-loader that knows original source files paths in Boot project."
  []
  (let [class-path (boot-fake-classpath)
        dir-separator (System/getProperty "file.separator")
        paths (str/split class-path (re-pattern (System/getProperty "path.separator")))
        urls (map
              (fn [path]
                (let [url (if (re-find #".jar$" path)
                            (str "file:" path)
                            (str "file:" path dir-separator))]
                  (new java.net.URL url)))
              paths)
        jdk-sources (->> [#_"see '## Classpath' notes at `orchard.java`"
                          ["src.zip"]
                          ["lib" "tools.jar"]]
                         (map (partial apply java/jdk-resource-url))
                         (remove nil?))]
    (new java.net.URLClassLoader (into-array java.net.URL (concat urls jdk-sources)))))

(defn class-loader
  []
  (if (boot-project?)
    (boot-class-loader)
    (.getContextClassLoader (Thread/currentThread))))
