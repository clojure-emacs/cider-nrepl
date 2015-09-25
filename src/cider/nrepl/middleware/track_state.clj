(ns cider.nrepl.middleware.track-state
  "State tracker for client sessions."
  {:author "Artur Malabarba"}
  (:require [cider.nrepl.middleware.ns :as ns]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :as misc]
            [cljs-tooling.util.analysis :as cljs-ana]
            [clojure.java.classpath :as cp]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport])
  (:import clojure.lang.Namespace
           clojure.tools.nrepl.transport.Transport))

(def clojure-core (try (find-ns 'clojure.core)
                       (catch Exception e nil)))

;;; Auxiliary
(defn update-vals
  "Update the keys of map `m` via the function `f`."
  [f m]
  (reduce (fn [acc [k v]]
            (assoc acc k (f v)))
          {} m))

(def relevant-meta-keys
  "Metadata keys that are useful to us.
  This is used so that we don't crowd the ns cache with useless or
  redudant information, such as :name and :ns."
  [:indent :cider-instrumented :macro :arglists :test])

(defn relevant-meta
  "Return the meta of var, selecting only keys of interest."
  [m]
  (->> (select-keys m relevant-meta-keys)
       (filter second)
       (update-vals pr-str)))

(defn filter-core-and-get-meta
  "Remove keys whose values are vars in the core namespace."
  [refers]
  (reduce (fn [^clojure.lang.PersistentHashMap acc [sym var]]
            (if (not (var? var))
              acc
              (let [^clojure.lang.PersistentHashMap m
                    (meta ^clojure.lang.Var var)]
                (if (identical? (:ns m) clojure-core)
                  acc
                  (assoc acc sym (relevant-meta m))))))
          {} refers))

;;; Namespaces
(def jar-namespaces
  (->> (cp/classpath-jarfiles)
       (mapcat ns-find/find-namespaces-in-jarfile)
       (into #{})))

(defn track-ns? [ns-name]
  (not (jar-namespaces ns-name)))

(defmulti ns-as-map
  "Return a map of useful information about ns."
  class)

;; Clojure Namespaces
(defmethod ns-as-map Namespace [^Namespace ns]
  (let [aliases (update-vals ns-name (.getAliases ns))]
    {:aliases aliases
     :interns (filter-core-and-get-meta (.getMappings ns))}))
;; ClojureScript Namespaces
(defmethod ns-as-map clojure.lang.Associative
  [{:keys [use-macros uses require-macros requires defs]}]
  (let [aliases (merge require-macros requires)]
    {:aliases aliases
     ;; For some reason, cljs (or piggieback) adds a :test key to the
     ;; var metadata stored in the namespace.
     :interns (update-vals #(dissoc (relevant-meta (meta %)) :test)
                           (merge defs uses use-macros))}))

(def clojure-core-map
  (when clojure-core
    {:aliases {}
     :interns (->> (.getMappings clojure-core)
                   (filter #(var? (second %)))
                   (update-vals #(relevant-meta (meta %))))}))

(defn calculate-changed-ns-map
  "Return a map of namespaces that changed between new and old-map.
  new is a list of namespaces objects, as returned by `all-ns`.
  old-map is a map from namespace names to namespace data, which is
  the same format of map returned by this function. old-map can also
  be nil, which is the same as an empty map."
  [new old-map]
  (reduce (fn [acc ns]
            (let [n (if (instance? Namespace ns)
                      (ns-name ns)
                      (:name ns))]
              (if-let [m (and (track-ns? n)
                              (ns-as-map ns))]
                (if (= (get old-map n) m)
                  acc
                  (assoc acc n m))
                acc)))
          ;; We want to inform the client of what's in clojure.core,
          ;; but we don't want to track changes. So we add it in when
          ;; the old-data is nil (meaning this is the first message).
          (if (and (not old-map) clojure-core-map)
            {'clojure.core clojure-core-map}
            {})
          new))

;;; State management
(defn calculate-used-aliases
  "Return a seq of namespaces aliased by a namespace in new-ns-map.
  The elements are symbols (namespace names).
  Skip any namespaces already present in new-ns-map or old-ns-map."
  [^clojure.lang.PersistentHashMap new-ns-map
   ^clojure.lang.PersistentHashMap old-ns-map]
  (->> (vals new-ns-map)
       (map :aliases)
       (mapcat vals)
       (reduce (fn [acc name]
                 (if (or (get acc name)
                         (get old-ns-map name)
                         (get new-ns-map name))
                   acc
                   (conj acc name)))
               [])))

(def ns-cache
  "Cache of the namespace info that has been sent to each session.
  Each key is a session. Each value is a map from namespace names to
  data (as returned by `ns-as-map`)."
  (agent {}
         :error-handler
         (fn [_ e]
           (println "Exception updating the ns-cache" e))))

(defn update-and-send-cache
  "Send a reply to msg with state information assoc'ed.
  old-data is the ns-cache that needs to be updated (the one
  associated with msg's session). Return the updated value for it.
  This function has side-effects (sending the message)!

  Two extra entries are sent in the reply. One is the :repl-type,
  which is either :clj or :cljs.

  The other is :changed-namespaces, which is a map from namespace
  names to namespace data (as returned by `ns-as-map`). This contains
  only namespaces which have changed since we last notified the
  client."
  [old-data msg]
  (let [cljs (cljs/grab-cljs-env msg)
        ;; See what has changed compared to the cache. If the cache
        ;; was empty, everything is considered to have changed (and
        ;; the cache will then be filled).
        changed-ns-map (-> (if cljs
                             (vals (cljs-ana/all-ns cljs))
                             (all-ns))
                           (calculate-changed-ns-map old-data))
        find-ns-fn (if cljs
                     #(cljs-ana/find-ns cljs %)
                     find-ns)
        used-aliases (->> (calculate-used-aliases changed-ns-map (or old-data {}))
                          (map #(vector % (ns-as-map (find-ns-fn %))))
                          (into {}))
        changed-ns-map (merge changed-ns-map used-aliases)]
    (->> (response-for
          msg :status :state
          :repl-type (if cljs :cljs :clj)
          :changed-namespaces (misc/transform-value changed-ns-map))
         (transport/send (:transport msg)))
    (merge old-data changed-ns-map)))

;;; Middleware
(defn make-transport
  "Return a Transport that defers to `transport` and possibly notifies
  about the state."
  [{:keys [^Transport transport session] :as msg}]
  (reify Transport
    (recv [this] (.recv transport))
    (recv [this timeout] (.recv transport timeout))
    (send [this {:keys [status] :as response}]
      (.send transport response)
      (when (contains? status :done)
        (send ns-cache update-in [session]
              update-and-send-cache msg)))))

(def ops-that-can-eval
  "Set of nREPL ops that can lead code being evaluated."
  #{"eval" "load-file" "refresh" "refresh-all" "refresh-clear" "undef"})

(defn wrap-tracker
  "Middleware that tracks relevant server info and notifies the client."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (ops-that-can-eval op)
      (handler (assoc msg :transport (make-transport msg)))
      (handler msg))))

(set-descriptor!
 #'wrap-tracker
 (cljs/expects-piggieback
  {:expects #{"eval"}
   :handles
   {"track-state-middleware"
    {:doc "Enhances the `eval` op by notifying the client of the current REPL state. Currently, only the REPL type (Clojure or ClojureScript) is informed."}}}))
