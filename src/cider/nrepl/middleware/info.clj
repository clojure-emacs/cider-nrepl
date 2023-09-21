(ns cider.nrepl.middleware.info
  (:require
   [compliment.context]
   [compliment.sources.class-members]
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [clojure.string :as str]
   [orchard.eldoc :as eldoc]
   [orchard.info :as info]
   [orchard.misc :as misc]))

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
                   (info/file-info file))
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

(defn info
  [{:keys [ns sym class member context]
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
                    (when (some-> sym (str/starts-with? "."))
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
                              :dialect :cljs}))]
    (cond
      java? (info/info-java class (or member sym))
      (and ns sym) (info/info* info-params)
      :else nil)))

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

(defn handle-info [handler msg]
  (with-safe-transport handler msg
    "info" info-reply
    "eldoc" eldoc-reply
    "eldoc-datomic-query" eldoc-datomic-query-reply))
