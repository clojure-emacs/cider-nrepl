(ns cider.nrepl.middleware.resource
  (:require
    [clojure.java.io :as io]
    [clojure.java.classpath :as classpath]
    [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
    [cider.nrepl.middleware.util.classloader :refer [class-loader]]))

(defn- trim-leading-separator
  [s]
  (if (.startsWith s java.io.File/separator)
    (subs s 1)
    s))

(defn- get-project-resources
  []
  (mapcat
    (fn [directory]
      (->>
        directory
        (file-seq)
        (filter (memfn isFile))
        (map (fn [file]
               (let [relpath (-> file
                                 (.getPath)
                                 (.replaceFirst
                                   (.getPath directory)
                                   "")
                                 (trim-leading-separator))]
                 {:root directory
                  :file file
                  :relpath relpath
                  :url (io/resource relpath)})))
        (remove #(.startsWith (:relpath %) "META-INF/"))
        (remove #(re-matches #".*\.(clj[cs]?|java|class)" (:relpath %)))))
    (filter (memfn isDirectory)
            (classpath/classpath (class-loader)))))

(defn resource-path [name]
  (when-let [resource (io/resource name (class-loader))]
    (.getPath resource)))

(defn resources-list [{:keys [prefix]}]
  (cond->> (map :relpath (get-project-resources))
    prefix
    (filter #(.startsWith % prefix))))

(defn resource-reply [{:keys [name] :as msg}]
  {:resource-path (resource-path name)})

(defn resources-list-reply [msg]
  {:resources-list (resources-list msg)})

(defn handle-resource [handler msg]
  (with-safe-transport handler msg
    "resource" resource-reply
    "resources-list" resources-list-reply))
