(ns cider.nrepl.middleware.resource
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [orchard.classpath :as cp]
   [orchard.misc :as u]))

(defn- boot-classloader
  "Creates a class-loader that knows original source files paths in Boot project."
  []
  (let [class-path (System/getProperty "fake.class.path")
        dir-separator (System/getProperty "file.separator")
        paths (str/split class-path (re-pattern (System/getProperty "path.separator")))
        urls (map
              (fn [path]
                (let [url (if (re-find #".jar$" path)
                            (str "file:" path)
                            (str "file:" path dir-separator))]
                  (new java.net.URL url)))
              paths)]
    ;; TODO: Figure out how to add the JDK sources here
    (new java.net.URLClassLoader (into-array java.net.URL urls))))

(defn- classloader
  []
  (if (System/getProperty "fake.class.path")
    (boot-classloader)
    (.getContextClassLoader (Thread/currentThread))))

(defn- trim-leading-separator
  [s]
  (if (.startsWith s java.io.File/separator)
    (subs s 1)
    s))

(defn- get-project-resources
  []
  (mapcat
   (fn [directory]
     (->> directory
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
   (filter (memfn isDirectory) (map io/as-file (cp/classpath (classloader))))))

(defn resource-path [name]
  (when-let [resource (io/resource name (classloader))]
    (.getPath resource)))

(defn resources-list
  "Return a list of dictionaries containing file and relpath: file is the
  absolute path to the resource, relpath is the path of the resource relative
  to the classpath."
  [_]
  (map #(select-keys % [:file :relpath])
       (get-project-resources)))

(defn resource-reply [{:keys [name] :as msg}]
  {:resource-path (resource-path name)})

(defn resources-list-reply [msg]
  {:resources-list (u/transform-value (resources-list msg))})

(defn handle-resource [handler msg]
  (with-safe-transport handler msg
    "resource" resource-reply
    "resources-list" resources-list-reply))
