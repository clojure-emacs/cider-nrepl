(ns cider.nrepl.middleware.track-state
  "State tracker for client sessions."
  {:author "Artur Malabarba"}
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.meta :as um]
   [orchard.cljs.analysis :as cljs-ana]
   [clojure.java.io :as io]
   [clojure.tools.namespace.find :as ns-find]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.java.classpath :as cp]
   [orchard.misc :as misc])
  (:import
   (clojure.lang Namespace MultiFn)
   java.net.SocketException
   java.util.jar.JarFile
   nrepl.transport.Transport))

(def clojure-core (try (find-ns 'clojure.core)
                       (catch Exception e nil)))

;;; Auxiliary

(defn- var-meta-with-fn
  "Like clojure.core/meta but adds {:fn true} for functions and macros.
  Should only be used for vars."
  [the-var]
  (cond-> (meta the-var)
    (or (fn? @the-var) (instance? MultiFn @the-var)) (assoc :fn true)))

(defn filter-core-and-get-meta
  "Remove keys whose values are vars in the core namespace."
  [refers]
  (->> refers
       (into {} (keep (fn [[sym the-var]]
                        (when (var? the-var)
                          (let [{the-ns :ns :as the-meta} (var-meta-with-fn the-var)]
                            (when-not (identical? the-ns clojure-core)
                              [sym (um/relevant-meta the-meta)]))))))))

(defn- cljs-meta-with-fn
  "Like (:meta m) but adds {:fn true} if (:fn-var m) is true, (:tag m) is
  'function, or if m does not have a :tag key."
  [m]
  (cond-> (:meta m)
    (or (:fn-var m)
        ('#{function cljs.core/MultiFn} (:tag m))
        (not (contains? m :tag)))
    (assoc :fn true)))

;;; Namespaces

(defn ns-as-map [object]
  (cond
    ;; Clojure Namespaces
    (instance? Namespace object)
    {:aliases (misc/update-vals ns-name (ns-aliases object))
     :interns (filter-core-and-get-meta (ns-map object))}

    ;; ClojureScript Namespaces
    (associative? object)
    (let [{:keys [use-macros uses require-macros requires defs]} object]
      {:aliases (merge require-macros requires)
       ;; For some reason, cljs (or piggieback) adds a :test key to the
       ;; var metadata stored in the namespace.
       :interns (merge (misc/update-vals #(dissoc (um/relevant-meta (cljs-meta-with-fn %)) :test) defs)
                       ;; FIXME: `uses` and `use-macros` are maps from
                       ;; symbols to namespace names:
                       ;;     {log reagent.debug, dbg reagent.debug}
                       ;; Since we don’t know the metadata for these
                       ;; vars, we resign to "guessing" them.
                       (misc/update-vals (constantly {:arglists '([])}) uses)
                       (misc/update-vals (constantly {:arglists '([]) :macro true}) use-macros))})

    :else {}))

(def clojure-core-map
  (when clojure-core
    {:aliases {}
     :interns (->> (ns-map clojure-core)
                   (filter #(var? (second %)))
                   (misc/update-vals #(um/relevant-meta (var-meta-with-fn %))))}))

(defn calculate-changed-ns-map
  "Return a map of namespaces that changed between new-map and old-map.
  new-map and old-map are maps from namespace names to namespace data,
  which is the same format of map returned by this function. old-map
  can also be nil, which is the same as an empty map."
  [new-map old-map]
  (into {}
        (keep (fn [[the-ns-name data]]
                (when-not (= (get old-map the-ns-name) data)
                  [the-ns-name data])))
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

(defn ensure-clojure-core-present
  "Check if `old-ns-map` has clojure.core, else add it to
  current-ns-map. If `cljs` we inject cljs.core instead. `cljs` is the
  cljs environment grabbed from the message (if present)."
  [old-ns-map project-ns-map cljs]
  (cond
    (and cljs (not (contains? old-ns-map 'cljs.core)))
    (assoc project-ns-map 'cljs.core
           (ns-as-map (cljs-ana/find-ns cljs "cljs.core")))

    ;; we have cljs and the cljs core, nothing to do
    cljs
    project-ns-map

    ;; we've got core in old or new
    (some #{clojure-core} (mapcat keys [old-ns-map project-ns-map]))
    project-ns-map

    :else
    (assoc project-ns-map clojure-core clojure-core-map)))

(def jar-namespaces
  (->> (cp/classpath)
       (filter misc/jar-file?)
       (map #(JarFile. (io/as-file %)))
       (mapcat ns-find/find-namespaces-in-jarfile)
       (into #{})))

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
  client.

  The 2-arity call is the intended way to use this function.

  The 4-arity call is provided for testing under mranderson.
  Allows substitution of supporting fns in the implementation that
  don't need to exposed otherwise. Be aware when the implementation
  details change because this arity (and the tests) will need to
  change also."
  ([old-data msg]
   (update-and-send-cache old-data msg
                          #'jar-namespaces
                          #'transport/send))
  ([old-data msg jar-ns-fn transport-send-fn]
   (let [cljs           (cljs/grab-cljs-env msg)
         find-ns-fn     (if cljs
                          #(cljs-ana/find-ns cljs %)
                          find-ns)
         ;; See what has changed compared to the cache. If the cache
         ;; was empty, everything is considered to have changed (and
         ;; the cache will then be filled).
         ns-name-fn     (if cljs :name ns-name)
         ;; Remove all jar namespaces.
         project-ns-map (fast-reduce (fn [acc ns]
                                       (let [name (ns-name-fn ns)]
                                         (if (jar-ns-fn name)
                                           acc
                                           (assoc! acc name (ns-as-map ns)))))
                                     (if cljs
                                       (vals (cljs-ana/all-ns cljs))
                                       (all-ns)))
         project-ns-map (ensure-clojure-core-present old-data
                                                     project-ns-map
                                                     cljs)
         changed-ns-map (-> project-ns-map
                            ;; Add back namespaces that the project depends on.
                            (merge-used-aliases (or old-data {}) find-ns-fn)
                            (calculate-changed-ns-map old-data))]
     (try (->> (response-for
                msg :status :state
                :repl-type (if cljs :cljs :clj)
                :changed-namespaces (util/transform-value changed-ns-map))
               (transport-send-fn (:transport msg)))
          ;; We run async, so the connection might have been closed in
          ;; the mean time.
          (catch SocketException _ nil))
     (merge old-data changed-ns-map))))

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

(defn handle-tracker [handler msg]
  (if (ops-that-can-eval (:op msg))
    (handler (assoc msg :transport (make-transport msg)))
    (handler msg)))
