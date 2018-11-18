(ns cider.nrepl.middleware.info
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [cljs-tooling.info :as cljs-info]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [orchard.eldoc :as eldoc]
   [orchard.info :as clj-info]
   [orchard.misc :as u]))

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
                   (clj-info/file-info file))
                 (when-let [path (:javadoc info)]
                   (clj-info/javadoc-info path)))
          format-nested
          blacklist
          u/transform-value))))

(defn info-cljs
  [env symbol ns]
  (some-> (cljs-info/info env symbol ns)
          (select-keys [:file :line :ns :doc :column :name :arglists])
          (update
           :file
           (fn [f]
             (if (u/boot-project?)
               ;; Boot stores files in a temporary directory & ClojureScript
               ;; stores the :file metadata location absolutely instead of
               ;; relatively to the classpath. This means when doing jump to
               ;; source in Boot & ClojureScript, you end up at the temp file.
               ;; This code attempts to find the classpath-relative location
               ;; of the file, so that it can be opened correctly.
               (let [path (java.nio.file.Paths/get f (into-array String []))
                     path-count (.getNameCount path)]
                 (or
                  (first
                   (sequence
                    (comp (map #(.subpath path % path-count))
                          (map str)
                          (filter io/resource))
                    (range path-count)))
                  f))
               f)))))


(defn info
  [{:keys [ns symbol class member] :as msg}]
  (let [[ns symbol class member] (map u/as-sym [ns symbol class member])]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (info-cljs cljs-env symbol ns)
      (let [var-info (cond (and ns symbol) (clj-info/info ns symbol)
                           (and class member) (clj-info/info-java class member)
                           :else (throw (Exception.
                                         "Either \"symbol\", or (\"class\", \"member\") must be supplied")))
            ;; we have to use the resolved (real) namespace and name here
            see-also (clj-info/see-also (:ns var-info) (:name var-info))]
        (if (seq see-also)
          (merge {:see-also see-also} var-info)
          (if-let [p (:protocol var-info)]
            (let [proto (deref p)
                  x (filter #(extends? proto
                                       %)
                            (filter #(class? %)
                                    (map val (mapcat ns-imports (all-ns)))))
                  xs (map (fn [x]
                            (let [xs (str/split (pr-str x)
                                                #"\.")]
                              (str (str/replace (str/join  "." (drop-last xs))
                                                #"\_"
                                                "-")
                                   "/map->"
                                   (last xs))))
                          x)]
              {:candidates (into {}
                                 (map (fn [s]
                                        [s (clj-info/info ns
                                                          (u/as-sym s))])
                                      xs))})
            var-info))))))


(defn info-reply
  [msg]
  (if-let [var-info (format-response (info msg))]
    var-info
    {:status :no-info}))

(defn eldoc-reply
  [msg]
  (if-let [info (info msg)]
    (eldoc/eldoc info)
    {:status :no-eldoc}))

(defn eldoc-datomic-query-reply
  [{:keys [ns symbol] :as msg}]
  (try
    (eldoc/datomic-query ns symbol)
    (catch Throwable _ {:status :no-eldoc})))

(defn handle-info [handler msg]
  (with-safe-transport handler msg
    "info" info-reply
    "eldoc" eldoc-reply
    "eldoc-datomic-query" eldoc-datomic-query-reply))
