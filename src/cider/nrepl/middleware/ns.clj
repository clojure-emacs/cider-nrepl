(ns cider.nrepl.middleware.ns
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cljs-tooling.util.analysis :as cljs-analysis]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [clojure.tools.namespace
             [file :refer [read-file-ns-decl]]
             [find :refer [clojure-sources-in-jar find-clojure-sources-in-dir]]]
            [clojure.tools.nrepl
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]
             [transport :as transport]])
  (:import java.util.jar.JarFile))

(defn ns-list-clj []
  (->> (all-ns)
       (map ns-name)
       (map name)
       (sort)))

(defn ns-vars-clj [ns]
  (->> (symbol ns)
       ns-publics
       keys
       (map name)
       sort))

(defn ns-list-cljs [env]
  (->> (cljs-analysis/all-ns env)
       keys
       (map name)
       sort))

(defn ns-vars-cljs [env ns]
  (->> (symbol ns)
       (cljs-analysis/public-vars env)
       keys
       (map name)
       sort))

(defn ns-list [msg]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-list-cljs cljs-env)
    (ns-list-clj)))

(defn ns-vars [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-cljs cljs-env ns)
    (ns-vars-clj ns)))

(defn ns-list-reply
  [{:keys [transport] :as msg}]
  (transport/send transport (response-for msg :ns-list (ns-list msg)))
  (transport/send transport (response-for msg :status :done)))

(defn ns-vars-reply
  [{:keys [transport] :as msg}]
  (transport/send transport (response-for msg :ns-vars (ns-vars msg)))
  (transport/send transport (response-for msg :status :done)))

(defn- jar-file?
  "Returns true if file is a normal file with a .jar or .JAR extension."
  [f]
  (let [file (io/file f)]
    (and (.isFile file)
         (.endsWith (.. file getName toLowerCase) ".jar"))))

(defn- get-clojure-sources-in-jar
  [^JarFile jar]
  (let [path-to-jar (.getName jar)]
    (map #(str "jar:file:" path-to-jar "!/" %) (clojure-sources-in-jar jar))))

(defn- all-clj-files-on-cp []
  (let [dirs-on-cp (filter #(.isDirectory %) (cp/classpath))
        jars-on-cp (map #(JarFile. %) (filter jar-file? (cp/classpath)))]
    (concat (->> dirs-on-cp
                 (mapcat find-clojure-sources-in-dir)
                 (map #(.getAbsolutePath %)))
            (mapcat get-clojure-sources-in-jar jars-on-cp))))

(defn- ns-path
  [{:keys [ns]}]
  (let [ns (symbol ns)]
    (loop [paths (all-clj-files-on-cp)]
      (when (seq paths)
        (let [file-ns (second (read-file-ns-decl (first paths)))]
          (if (= file-ns ns)
            (first paths)
            (recur (rest paths))))))))

(defn- ns-path-reply
  [{:keys [transport] :as msg}]
  (when-let [path (ns-path msg)]
    (transport/send transport (response-for msg :path (ns-path msg))))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-ns
  "Middleware that provides ns listing/browsing functionality."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "ns-list" (ns-list-reply msg)
      "ns-vars" (ns-vars-reply msg)
      "ns-path" (ns-path-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-ns
 (cljs/requires-piggieback
  {:handles
   {"ns-list"
    {:doc "Return a sorted list of all namespaces."
     :returns {"status" "done"}}
    "ns-vars"
    {:doc "Returns a sorted list of all vars in a namespace."
     :requires {"ns" "The namespace to browse."}
     :returns {"status" "done"}}
    "ns-path"
    {:doc "Returns the path to the file containing ns."
     :requires {"ns" "The namespace to find."}
     :return {"status" "done" "path" "The path to the file containing ns."}}}}))
