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
           clojure.tools.nrepl.transport.Transport
           java.net.SocketException))

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
  [:indent :deprecated :macro :arglists :test
   :cider.nrepl.middleware.util.instrument/breakfunction
   :style/indent :clojure.tools.trace/traced])

(defn relevant-meta
  "Return the meta of var, selecting only keys of interest."
  [m]
  (->> (select-keys m relevant-meta-keys)
       (filter second)
       (update-vals pr-str)))

(defn filter-core-and-get-meta
  "Remove keys whose values are vars in the core namespace."
  [refers]
  (->> refers
       (into {} (keep (fn [[sym the-var]]
                        (when (var? the-var)
                          (let [{the-ns :ns :as the-meta} (meta the-var)]
                            (when-not (identical? the-ns clojure-core)
                              [sym (relevant-meta the-meta)]))))))))

;;; Namespaces
(def jar-namespaces
  (->> (cp/classpath-jarfiles)
       (mapcat ns-find/find-namespaces-in-jarfile)
       (into #{})))

(defn track-ns? [ns-name]
  (not (jar-namespaces ns-name)))

(defn ns-as-map [object]
  (cond
    ;; Clojure Namespaces
    (instance? Namespace object)
    {:aliases (update-vals ns-name (ns-aliases object))
     :interns (filter-core-and-get-meta (ns-map object))}

    ;; ClojureScript Namespaces
    (associative? object)
    (let [{:keys [use-macros uses require-macros requires defs]} object]
      {:aliases (merge require-macros requires)
       ;; For some reason, cljs (or piggieback) adds a :test key to the
       ;; var metadata stored in the namespace.
       :interns (update-vals #(dissoc (relevant-meta (meta %)) :test)
                             (merge defs uses use-macros))})

    :else {}))

(def clojure-core-map
  (when clojure-core
    {:aliases {}
     :interns (->> (ns-map clojure-core)
                   (filter #(var? (second %)))
                   (update-vals #(relevant-meta (meta %))))}))

(defn calculate-changed-ns-map
  "Return a map of namespaces that changed between new-map and old-map.
  new-map and old-map are maps from namespace names to namespace data,
  which is the same format of map returned by this function. old-map
  can also be nil, which is the same as an empty map."
  [new-map old-map]
  (into (if (or (seq old-map)
                (not clojure-core-map))
          {}
          ;; We want to inform the client of what's in clojure.core,
          ;; but we don't want to track changes. So we add it in when
          ;; the old-data is nil (meaning this is the first message).
          {'clojure.core clojure-core-map})
        (keep (fn [[the-ns-name the-ns]]
                (when-let [data (ns-as-map the-ns)]
                  (when-not (= (get old-map the-ns-name) data)
                    [the-ns-name data]))))
        new-map))

;;; State management
(defn merge-used-aliases
  "Return new-ns-map merged with all of its direct dependencies.
  val-fn a function that returns namespace objects when called with
  namespace names."
  [^clojure.lang.PersistentHashMap new-ns-map
   ^clojure.lang.PersistentHashMap old-ns-map
   val-fn]
  (->> (vals new-ns-map)
       (map :aliases)
       (mapcat vals)
       (reduce (fn [acc name]
                 (if (or (get acc name)
                         (get old-ns-map name))
                   acc
                   (assoc acc name (ns-as-map (val-fn name)))))
               new-ns-map)))

(def ns-cache
  "Cache of the namespace info that has been sent to each session.
  Each key is a session. Each value is a map from namespace names to
  data (as returned by `ns-as-map`)."
  (agent {}
         :error-handler
         (fn [_ e]
           (println "Exception updating the ns-cache" e))))

(defn fast-reduce
  "Like (reduce f {} coll), but faster.
  Inside f, use `assoc!` and `conj!` instead of `assoc` and `conj`."
  [f coll]
  (persistent! (reduce f (transient {}) coll)))

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
        find-ns-fn (if cljs
                     #(cljs-ana/find-ns cljs %)
                     find-ns)
        ;; See what has changed compared to the cache. If the cache
        ;; was empty, everything is considered to have changed (and
        ;; the cache will then be filled).
        ns-name-fn (if cljs :name ns-name)
        ;; Remove all jar namespaces.
        project-ns-map (fast-reduce (fn [acc ns]
                                      (let [name (ns-name-fn ns)]
                                        (if (track-ns? name)
                                          (assoc! acc name ns)
                                          acc)))
                                    (if cljs
                                      (vals (cljs-ana/all-ns cljs))
                                      (all-ns)))
        changed-ns-map (-> project-ns-map
                           ;; Add back namespaces that the project depends on.
                           (merge-used-aliases (or old-data {}) find-ns-fn)
                           (calculate-changed-ns-map old-data))]
    (try (->> (response-for
               msg :status :state
               :repl-type (if cljs :cljs :clj)
               :changed-namespaces (misc/transform-value changed-ns-map))
              (transport/send (:transport msg)))
         ;; We run async, so the connection might have been closed in
         ;; the mean time.
         (catch SocketException _ nil))
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
  "Set of nREPL ops that can lead to code being evaluated."
  #{"eval" "load-file" "refresh" "refresh-all" "refresh-clear"
    "toggle-trace-var" "toggle-trace-ns" "undef"})

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
  {:expects ops-that-can-eval
   :handles
   {"track-state-middleware"
    {:doc "Enhances the `eval` op by notifying the client of the current REPL state. Currently, only the REPL type (Clojure or ClojureScript) is informed."}}}))
