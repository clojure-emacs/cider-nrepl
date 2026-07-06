;; Download JDK sources from Github and repackage in the same fashion as src.zip
;; that is normally distributed with JDK.

(ns build.download-jdk-sources
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.tools.build.tasks.zip :as zip])
  (:import (java.net URI)
           (java.io File FileInputStream FileOutputStream BufferedInputStream)
           (java.nio.file Files Path Paths CopyOption StandardCopyOption FileVisitResult)
           (java.nio.file.attribute BasicFileAttributes)
           (java.util.zip ZipInputStream ZipOutputStream ZipEntry)
           (java.nio.file SimpleFileVisitor)))

(def url
  (case (System/getProperty "java.specification.version")
    "11" "https://github.com/adoptium/jdk11u/archive/refs/tags/jdk-11.0.28+0.zip"
    "17" "https://github.com/adoptium/jdk17u/archive/refs/tags/jdk-17.0.15+5.zip"
    "21" "https://github.com/adoptium/jdk21u/archive/refs/tags/jdk-21.0.7+5.zip"
    "25" "https://github.com/adoptium/jdk25u/archive/refs/tags/jdk-25.0.4+6.zip"
    "26" "https://github.com/adoptium/jdk/archive/refs/tags/jdk-26+35.zip"
    nil))

(defn path [& parts]
  (Paths/get (first parts) (into-array String (rest parts))))

(defn download [url dest-path]
  (println "Downloading" url "...")
  (with-open [in  (-> (URI. url) .toURL .openStream BufferedInputStream.)
              out (FileOutputStream. (str dest-path))]
    (io/copy in out :buffer-size 102400)))

#_(download url "full-src.zip")

(defn unzip [zip-path target-dir]
  (println "Extracting" zip-path "...")
  (zip/unzip {:zip-file zip-path :target-dir target-dir}))

#_(unzip "full-src.zip" "tmp")

(defn find-jdk-dir [base]
  (some #(when (str/starts-with? (.getName ^File %) "jdk") %)
        (.listFiles (io/file base))))

#_(find-jdk-dir "tmp")

(defn add-dir-to-zip [zos ^Path source-dir prefix]
  (Files/walkFileTree
   source-dir
   (proxy [SimpleFileVisitor] []
     (visitFile [file _attrs]
       (let [entry-name (str prefix "/" (.relativize source-dir file))]
         (.putNextEntry zos (ZipEntry. entry-name))
         (Files/copy file zos)
         (.closeEntry zos))
       FileVisitResult/CONTINUE)
     (preVisitDirectory [dir _attrs]
       FileVisitResult/CONTINUE))))

(defn zip-dirs [dirs root-dir dest-path]
  (println "Creating " dest-path "...")
  (with-open [zos (ZipOutputStream. (FileOutputStream. (str dest-path)))]
    (doseq [[prefix rel-dir] dirs
            :let [dir-path (io/file root-dir rel-dir)]]
      (when (.exists dir-path)
        (add-dir-to-zip zos (.toPath dir-path) prefix)))))

(def dirs [["java.base"    "src/java.base/share/classes"]
           ["java.desktop" "src/java.desktop/share/classes"]
           ["java.sql"     "src/java.sql/share/classes"]])

#_(zip-dirs dirs (io/file "tmp/src.zip"))

(defn download-and-repackage-sources [dest]
  (cond (.exists (io/file dest))
        (println "File" dest "already exists, skipping...")

        (nil? url)
        (println "Not downloading sources for this JDK version.")

        :else
        (do
          (def tmp-dir (doto (io/file "tmp") .mkdirs))
          (def full-src-file (io/file tmp-dir "full-src.zip"))
          (download url full-src-file)
          (unzip full-src-file tmp-dir)
          (def jdk-dir (find-jdk-dir tmp-dir))
          (zip-dirs dirs jdk-dir dest)
          (b/delete {:path (str tmp-dir)}))))
