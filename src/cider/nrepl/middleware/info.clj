(ns cider.nrepl.middleware.info
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [orchard.eldoc :as eldoc]
   [orchard.info :as info]
   [orchard.misc :as misc]))

(declare format-response)

(defn dissoc-nil-keys
  "Dissociate keys which has nil as a value to avoid returning empty list as a nil.
  nrepl/bencode converts nil to empty list."
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
                   (info/file-info file))
                 (when-let [path (:javadoc info)]
                   (info/javadoc-info path)))
          dissoc-nil-keys
          format-nested
          blacklist
          util/transform-value))))

(defn info
  [{:keys [ns symbol class member] :as msg}]
  (let [[ns symbol class member] (map misc/as-sym [ns symbol class member])
        env (cljs/grab-cljs-env msg)
        info-params (merge {:dialect :clj
                            :ns ns
                            :sym symbol}
                           (when env
                             {:env env
                              :dialect :cljs}))]
    (cond
      (and ns symbol) (info/info* info-params)
      (and class member) (info/info-java class member)
      :else (throw (Exception.
                    "Either \"symbol\", or (\"class\", \"member\") must be supplied")))))

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
