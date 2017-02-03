(ns cider.nrepl.middleware.info
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.javadoc :as javadoc]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [cider.nrepl.middleware.util.java :as java]
            [cider.nrepl.middleware.util.namespace :as ns]
            [cider.nrepl.middleware.util.misc :as u]
            [clojure.repl :as repl]
            [cljs-tooling.info :as cljs-info]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [cider.nrepl.middleware.util.spec :as spec]))

(defn- boot-project? []
  ;; fake.class.path under boot contains the original directories with source
  ;; files, see https://github.com/boot-clj/boot/issues/249
  (not (nil? (System/getProperty "fake.class.path"))))

(defn- boot-class-loader
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
    (new java.net.URLClassLoader (into-array java.net.URL urls))))

(defn- resource-full-path [relative-path]
  (if (u/boot-project?)
    (io/resource relative-path (boot-class-loader))
    (io/resource relative-path)))

(defn maybe-protocol
  [info]
  (if-let [prot-meta (meta (:protocol info))]
    (merge info {:file (:file prot-meta)
                 :line (:line prot-meta)})
    info))

(def var-meta-whitelist
  [:ns :name :doc :file :arglists :forms :macro
   :protocol :line :column :static :added :deprecated :resource])

(defn- map-seq [x]
  (if (seq x)
    x
    nil))

(defn maybe-add-file
  "If `meta-map` has no :file, assoc the :namespace file into it."
  [{:keys [file ns] :as meta-map}]
  ;; If we don't know its file, use the ns file.
  (if (and ns (or (not file)
                  (re-find #"/form-init[^/]*$" file)))
    (-> (dissoc meta-map :line)
        (assoc :file (ns/ns-path ns)))
    meta-map))

(defn- format-spec-descripton
  "Format the spec description to display each predicate on a new line."
  [description]
  (if (seq? description)
    ;; drop the first element, format everything else with newlines and tabs,
    ;; and add the surrounding parens
    (let [beg (format "(%s " (pr-str (first description)))
          desc (drop 1 description)]
      (format "%s%s)" beg (str/join (map #(format "\n\t%s" (pr-str %)) desc))))
    (pr-str description)))

;; Similar to `print-doc` from clojure.core
;; https://github.com/clojure/clojure/blob/master/src/clj/clojure/repl.clj#L83
(defn- format-spec
  [fnspec]
  (for [role [:args :ret :fn]
        :let [spec' (get fnspec role)]
        :when spec']
    (let [desc (spec/describe spec')]
      ;; the -4s aligns the fdef parameters (args, ret and fn)
      (str (format "%-4s: " (name role)) (format-spec-descripton desc)))))

(defn maybe-add-spec
  "If the var `v` has a spec has associated with it, assoc that into meta-map.
  The spec is formatted to avoid processing it in CIDER."
  [v meta-map]
  (if-let [fnspec (spec/get-spec v)]
    (merge meta-map {:spec (format-spec fnspec)})
    meta-map))

(defn var-meta
  [v]
  (let [meta-map (-> v meta maybe-protocol
                     (select-keys var-meta-whitelist)
                     map-seq maybe-add-file)]
    (maybe-add-spec v meta-map)))

(defn ns-meta
  [ns]
  (merge
   (meta ns)
   {:ns ns
    :file (-> (ns-publics ns)
              first
              second
              var-meta
              :file)
    :line 1}))

(defn resolve-var
  [ns sym]
  (if-let [ns (find-ns ns)]
    (try (ns-resolve ns sym)
         ;; Impl might try to resolve it as a class, which may fail
         (catch ClassNotFoundException _
           nil)
         ;; TODO: Preserve and display the exception info
         (catch Exception _
           nil))))

(defn resolve-aliases
  [ns]
  (if-let [ns (find-ns ns)]
    (ns-aliases ns)))

;; This reproduces the behavior of the canonical `clojure.repl/doc` using its
;; internals, but returns the metadata rather than just printing. Oddly, the
;; only place in the Clojure API that special form metadata is available *as
;; data* is a private function. Lame. Just call through the var.
(defn resolve-special
  "Return info for the symbol if it's a special form, or nil otherwise. Adds
  `:url` unless that value is explicitly set to `nil` -- the same behavior
  used by `clojure.repl/doc`."
  [sym]
  (try
    (let [sym (get '{& fn, catch try, finally try} sym sym)
          v   (meta (ns-resolve (find-ns 'clojure.core) sym))]
      (when-let [m (cond (special-symbol? sym) (#'repl/special-doc sym)
                         (:special-form v) v)]
        (assoc m
               :url (if (contains? m :url)
                      (when (:url m)
                        (str "https://clojure.org/" (:url m)))
                      (str "https://clojure.org/special_forms#" (:name m))))))
    (catch NoClassDefFoundError _)
    (catch Exception _)))

(defn find-cljx-source
  "If file was cross-compiled using CLJX, return path to original file."
  [filename]
  (let [file  (if-let [rsrc (resource-full-path filename)]
                (when (= "file" (.getProtocol rsrc))
                  (io/as-file rsrc))
                (io/as-file filename))]
    (when (and file (.exists file) (.isFile file))
      (with-open [in (io/input-stream file)]
        (.skip in (max 0 (- (.length file) 1024)))
        (->> (slurp in)
             (re-find #";+ This file autogenerated from (.*)$")
             second)))))

(defn handle-cljx-sources
  "If file in info is a result of crosscompilation, replace file
   with .cljx source."
  [info]
  (if (:file info)
    (update-in info [:file] #(or (find-cljx-source %) %))
    info))

(def see-also-data
  (edn/read-string (slurp (io/resource "see-also.edn"))))

(defn info-clj
  [ns sym]
  (cond
    ;; it's a special (special-symbol? or :special-form)
    (resolve-special sym) (resolve-special sym)
    ;; it's a var
    (var-meta (resolve-var ns sym)) (var-meta (resolve-var ns sym))
    ;; sym is an alias for another ns
    (get (resolve-aliases ns) sym) (ns-meta (get (resolve-aliases ns) sym))
    ;; it's simply a full ns
    (find-ns sym) (ns-meta (find-ns sym))
    ;; it's a Java class/member symbol...or nil
    :else (java/resolve-symbol ns sym)))

(defn info-cljs
  [env symbol ns]
  (some-> (cljs-info/info env symbol ns)
          (select-keys [:file :line :ns :doc :column :name :arglists])))

(defn info-java
  [class member]
  (java/member-info class member))

(defn info
  [{:keys [ns symbol class member] :as msg}]
  (let [[ns symbol class member] (map u/as-sym [ns symbol class member])]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (handle-cljx-sources (info-cljs cljs-env symbol ns))
      (let [var-info (cond (and ns symbol) (handle-cljx-sources (info-clj ns symbol))
                           (and class member) (info-java class member)
                           :else (throw (Exception.
                                         "Either \"symbol\", or (\"class\", \"member\") must be supplied")))
            see-also (get see-also-data (str (:ns var-info) "/" (:name var-info)))]
        (if see-also
          (merge {:see-also see-also} var-info)
          var-info)))))

(defn resource-path
  "If it's a resource, return a tuple of the relative path and the full resource path."
  [x]
  (or (if-let [full (resource-full-path x)]
        [x full])
      (if-let [[_ relative] (re-find #".*jar!/(.*)" x)]
        (if-let [full (resource-full-path relative)]
          [relative full]))
      ;; handles load-file on jar resources from a cider buffer
      (if-let [[_ relative] (re-find #".*jar:(.*)" x)]
        (if-let [full (resource-full-path relative)]
          [relative full]))))

(defn file-path
  "For a file path, return a URL to the file if it exists and does not
  represent a form evaluated at the REPL."
  [x]
  (when (seq x)
    (let [f (io/file x)]
      (when (and (.exists f)
                 (not (-> f .getName (.startsWith "form-init"))))
        (io/as-url f)))))

(defn file-info
  [path]
  (let [[resource-relative resource-full] (resource-path path)]
    (merge {:file (or (file-path path) resource-full path)}
           ;; Classpath-relative path if possible
           (if resource-relative
             {:resource resource-relative}))))

(defn javadoc-info
  "Resolve a relative javadoc path to a URL and return as a map. Prefer javadoc
  resources on the classpath; then use online javadoc content for core API
  classes. If no source is available, return the relative path as is."
  [path]
  {:javadoc
   (or (resource-full-path path)
       ;; [bug#308] `*remote-javadocs*` is outdated WRT Java
       ;; 8, so we try our own thing first.
       (when (re-find #"^(java|javax|org.omg|org.w3c.dom|org.xml.sax)/" path)
         (format "http://docs.oracle.com/javase/%s/docs/api/%s"
                 u/java-api-version path))
       ;; If that didn't work, _then_ we fallback on `*remote-javadocs*`.
       (some (let [classname (.replaceAll path "/" ".")]
               (fn [[prefix url]]
                 (when (.startsWith classname prefix)
                   (str url path))))
             @javadoc/*remote-javadocs*)
       path)})

(javadoc/add-remote-javadoc "com.amazonaws." "http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/")
(javadoc/add-remote-javadoc "org.apache.kafka." "https://kafka.apache.org/090/javadoc/index.html?")

(declare format-response)

(defn format-nested
  "Apply response formatting to nested `:candidates` info for Java members."
  [info]
  (if-let [candidates (:candidates info)]
    (assoc info :candidates
           (zipmap (keys candidates)
                   (->> (vals candidates) (map format-response))))
    info))

(defn blacklist
  "Remove anything that might contain arbitrary EDN, metadata can hold anything"
  [info]
  (let [blacklisted #{:arglists :forms}]
    (apply dissoc info blacklisted)))

(defn format-response
  [info]
  (when info
    (-> info
        (merge (when-let [ns (:ns info)]
                 {:ns (str ns)})
               (when-let [args (:arglists info)]
                 {:arglists-str (pr-str args)})
               (when-let [forms (:forms info)]
                 {:forms-str (->> (map #(str "  " (pr-str %)) forms)
                                  (str/join \newline))})
               (when-let [file (:file info)]
                 (file-info file))
               (when-let [path (:javadoc info)]
                 (javadoc-info path)))
        format-nested
        blacklist
        u/transform-value)))

(defn info-reply
  [msg]
  (if-let [var-info (format-response (info msg))]
    var-info
    {:status :no-info}))

(defn extract-arglists
  [info]
  (cond
    (:special-form info) (->> (:forms info)
                              ;; :forms contains a vector of sequences or symbols
                              ;; which we have to convert the format employed by :arglists
                              (map #(if (coll? %) (vec %) (vector %))))
    (:candidates info) (->> (:candidates info)
                            vals
                            (mapcat :arglists)
                            distinct
                            (sort-by count))
    :else (:arglists info)))

(defn format-arglists [raw-arglists]
  (map #(mapv str %) raw-arglists))

(defn extract-ns-or-class
  [{:keys [ns class candidates] :as info}]
  (cond
    ns {:ns (str ns)}
    class {:class [(str class)]}
    candidates {:class (map key candidates)}))

(defn extract-name-or-member
  [{:keys [name member candidates]}]
  (cond
    name {:name (str name)}
    member {:member (str member)}
    candidates {:member (->> candidates vals (map :member) first str)}))

(defn extract-eldoc
  [info]
  (if-let [arglists (seq (-> info extract-arglists format-arglists))]
    {:eldoc arglists :type "function"}
    {:type "variable"}))

(defn eldoc-reply
  [msg]
  (if-let [info (info msg)]
    (merge (extract-ns-or-class info)
           (extract-name-or-member info)
           (extract-eldoc info)
           {:docstring (:doc info)})
    {:status :no-eldoc}))

(defn eldoc-datomic-query-reply
  [msg]
  (try
    (let [ns (read-string (:ns msg))
          sym (read-string (:symbol msg))
          query (if (symbol? sym)
                  (deref (ns-resolve ns sym))
                  (eval sym))
          inputs (if (map? query)
                   ;; query as map
                   (or (:in query) "$")
                   ;; query as vector
                   (let [partitioned (partition-by keyword? query)
                         index (.indexOf partitioned '(:in))]
                     (if (= index -1)
                       "$"
                       (nth partitioned (+ 1 index)))))]
      {:inputs (format-arglists [inputs])})
    (catch Throwable _ {:status :no-eldoc})))

(defn wrap-info
  "Middleware that looks up info for a symbol within the context of a particular namespace."
  [handler]
  (with-safe-transport handler
    "info" info-reply
    "eldoc" eldoc-reply
    "eldoc-datomic-query" eldoc-datomic-query-reply))

(set-descriptor!
 #'wrap-info
 (cljs/requires-piggieback
  {:handles
   {"info"
    {:doc "Return a map of information about the specified symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}
    "eldoc"
    {:doc "Return a map of information about the specified symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}
    "eldoc-datomic-query"
    {:doc "Return a map containing the inputs of the datomic query."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}}}))
