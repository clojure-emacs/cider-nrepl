(ns cider.nrepl.middleware.info
  (:require
   [compliment.context]
   [compliment.sources.class-members]
   [cider.nrepl.middleware.util :as util :refer [respond-to]]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [clojure.string :as str]
   [orchard.cljs.analysis :as cljs-ana]
   [orchard.eldoc :as eldoc]
   [orchard.info :as info]
   [orchard.java.source-files :as src-files]
   [orchard.meta :as meta]
   [orchard.misc :as misc])
  (:import
   (java.util.concurrent ConcurrentHashMap)))

(declare format-response)

(defn dissoc-nil-keys
  "Dissociate keys which has nil as a value to avoid returning empty list as a nil.
  nrepl.bencode converts nil to empty list."
  [info]
  (reduce-kv
   (fn [res k v]
     (cond-> res
       (some? v) (assoc k v)))
   {} info))

(defn format-nested
  "Apply response formatting to nested `:candidates` info for Java members."
  [info]
  (if-let [candidates (:candidates info)]
    (assoc info :candidates
           (zipmap (keys candidates)
                   (->> (vals candidates) (map format-response))))
    info))

(defn blacklist
  "Remove anything that might contain arbitrary EDN, metadata can hold anything."
  [info]
  (let [blacklisted #{:arglists :forms}]
    (apply dissoc info blacklisted)))

(defn format-response
  [info]
  (letfn [(forms-join [forms]
            (->> (map pr-str forms)
                 (str/join \newline)))]
    (when info
      (-> info
          (merge (when-let [ns (:ns info)]
                   {:ns (str ns)})
                 (when-let [args (:arglists info)]
                   {:arglists-str (forms-join args)})
                 (when-let [forms (:forms info)]
                   {:forms-str (forms-join forms)})
                 (when-let [file (:file info)]
                   (info/file-info (str file)))
                 (when-let [path (:javadoc info)]
                   (info/javadoc-info path)))
          dissoc-nil-keys
          format-nested
          blacklist
          util/transform-value))))

(defn- extract-class-from-compliment
  "Given a Compliment-style `context`, returns the inferred class name
  of the object placed at __prefix__."
  [ns-str context]
  (when (and (seq ns-str)
             (seq context))
    (try
      (when-let [ns-obj (find-ns (symbol ns-str))]
        (let [c (compliment.context/cache-context context)
              ^Class c (compliment.sources.class-members/try-get-object-class ns-obj c)]
          (some-> c .getName)))
      (catch Exception _
        ;; We can always be analyzing a broken context.
        nil))))

(def var-meta-allowlist-set
  (set meta/var-meta-allowlist))

(def ^:private ^ConcurrentHashMap attempted-to-download-coords
  "Map that keeps all Maven artifact coordinates that we already attempted to
  download once (doesn't matter the result). We don't want to repeatedly try to
  download an artifact that doesn't exist or can't be found."
  (ConcurrentHashMap.))

(defn- download-sources-jar-for-class [klass msg]
  (when-let [coords (src-files/infer-maven-coordinates-for-class klass)]
    (when (nil? (.putIfAbsent attempted-to-download-coords coords true))
      ;; Tell the client we are going to download an artifict so it can notify
      ;; the user. It may take a few seconds.
      (respond-to msg {:status :download-sources-jar
                       :coords coords})
      (src-files/download-sources-jar-for-coordinates coords))))

(defn info
  [{:keys [ns sym class member context var-meta-allowlist download-sources-jar]
    legacy-sym :symbol
    :as msg}]
  (let [sym (or (not-empty legacy-sym)
                (not-empty sym))
        class (try
                (or (when (and (seq class)
                               (seq ns)
                               (find-ns (symbol ns)))
                      (some-> ^Class (ns-resolve (find-ns (symbol ns))
                                                 (symbol class))
                              .getName))
                    (not-empty class)
                    (when (and (some-> sym (str/starts-with? "."))
                               ;; .. cannot be a class member, so class inference doesn't make sense here:
                               (not= sym ".."))
                      (extract-class-from-compliment ns context)))
                (catch Exception e
                  nil))
        java? (seq class)
        [ns sym class member] (mapv misc/as-sym [ns
                                                 (cond-> sym
                                                   (and (seq sym)
                                                        java?)
                                                   (str/replace #"^\." ""))
                                                 class
                                                 member])
        env (cljs/grab-cljs-env msg)
        info-params (merge {:dialect :clj
                            :ns ns
                            :sym sym}
                           (when env
                             {:env env
                              :dialect :cljs})
                           (when var-meta-allowlist
                             {:var-meta-allowlist (into meta/var-meta-allowlist
                                                        (remove var-meta-allowlist-set)
                                                        var-meta-allowlist)}))]
    ;; If the client requested sources jar to be downloaded in case it is
    ;; missing, try to download it once using `orchard.java.src-files`.
    (binding [src-files/*download-sources-jar-fn*
              (when download-sources-jar
                #(download-sources-jar-for-class % msg))]
      (cond
        java? (info/info-java class (or member sym))
        (and ns sym) (info/info* info-params)
        :else nil))))

(defn info-reply
  [msg]
  (if-let [var-info (format-response (info msg))]
    var-info
    {:status :no-info}))

(defn eldoc-reply
  [msg]
  (if-let [{:keys [doc-first-sentence-fragments doc-fragments doc-block-tags-fragments] :as info} (info msg)]
    (cond-> (eldoc/eldoc info)
      (seq doc-fragments)                (assoc :doc-fragments doc-fragments)
      (seq doc-first-sentence-fragments) (assoc :doc-first-sentence-fragments doc-first-sentence-fragments)
      (seq doc-block-tags-fragments)     (assoc :doc-block-tags-fragments doc-block-tags-fragments))
    {:status :no-eldoc}))

(defn eldoc-datomic-query-reply
  [{:keys [ns sym symbol]}]
  (try
    (eldoc/datomic-query ns (or sym symbol))
    (catch Throwable _ {:status :no-eldoc})))

(defn- classify-symbols-clj
  "Return a map from each symbol string in `symbols` to its classification in
  `ns` (see `orchard.meta/classify-symbol`), omitting symbols that don't
  resolve."
  [ns symbols]
  (let [ns (or (misc/as-sym ns) 'user)]
    (reduce (fn [acc s]
              (if-let [kind (meta/classify-symbol ns (symbol s))]
                (assoc acc s (name kind))
                acc))
            {}
            symbols)))

(defn- classify-symbol-cljs
  "Classify the symbol `sym` resolved in the ClojureScript namespace `ns`.
  Uses read-only `orchard.cljs.analysis` lookups (rather than the heavier
  `orchard.info/info*`, which mutates the shared compiler env).  ClojureScript
  has no inline functions, so a resolved var that isn't a macro is a function.
  Namespace-qualified macros aren't recognized yet."
  [env ns sym]
  (cond
    (or (get (cljs-ana/core-macros env ns) sym)
        (get (cljs-ana/referred-macros env ns) sym)) "macro"
    (or (cljs-ana/ns-resolve env ns sym)
        (get (cljs-ana/core-vars env ns) sym)) "function"
    (cljs-ana/special-meta env sym) "special"))

(defn- classify-symbols-cljs
  "Return a map from each macroexpandable symbol string in `symbols` to its
  classification in the ClojureScript namespace `ns`, omitting symbols that
  don't resolve."
  [env ns symbols]
  (let [ns (or (misc/as-sym ns) 'cljs.user)]
    (reduce (fn [acc s]
              (if-let [kind (try (classify-symbol-cljs env ns (symbol s))
                                 (catch Exception _ nil))]
                (assoc acc s kind)
                acc))
            {}
            symbols)))

(defn classify-symbols
  "Classify each symbol string in `:symbols`, resolved in `:ns`.
  Returns a map from symbol string to its kind (\"macro\", \"inline\",
  \"special\" or \"function\"); symbols that don't resolve are omitted."
  [{:keys [symbols] :as msg}]
  (if-let [env (cljs/grab-cljs-env msg)]
    (classify-symbols-cljs env (:ns msg) symbols)
    (classify-symbols-clj (:ns msg) symbols)))

(defn classify-symbols-reply [msg]
  {:classification (classify-symbols msg)})

(defn handle-info [handler msg]
  (with-safe-transport handler msg
    (merge
     (with-op-aliases {"cider/info" info-reply
                       "cider/eldoc" eldoc-reply
                       "cider/eldoc-datomic-query" eldoc-datomic-query-reply})
     {"cider/classify-symbols" classify-symbols-reply})))
