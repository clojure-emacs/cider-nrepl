(ns cider.nrepl.middleware.track-state
  "State tracker for client sessions."
  {:author "Artur Malabarba"}
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.meta :as um]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.namespace.find :as ns-find]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.cljs.analysis :as cljs-ana]
   [orchard.indent :as indent]
   [orchard.java.classpath :as cp]
   [orchard.misc :as misc])
  (:import
   (clojure.lang MultiFn Namespace)
   (java.net SocketException)
   (java.util.jar JarFile)
   (nrepl.transport Transport)))

(def clojure-core (try (find-ns 'clojure.core)
                       (catch Exception _e nil)))

;;; Auxiliary

(defn- inferrable-indent?
  "Does metadata map `m` lack, need a `:style/indent` value, and is a suitable candidate for it?"
  [m]
  (and (:macro m)
       (:arglists m)
       (not (:style/indent m))
       (not (:indent m))
       (if-let [namespace-name (some-> (cond
                                         (instance? Namespace (:ns m)) ;; JVM clojure
                                         (-> m :ns ns-name)

                                         (symbol? (:ns m)) ;; possible edge cases
                                         (:ns m)

                                         ;; cljs analyzer info
                                         :else
                                         (some-> m :name namespace symbol))
                                       str)]
         ;; Official Clojure libraries are unlikely to ever include :style/indent metadata.
         ;; So our normal recommendation of adding it to any macro cannot apply here.
         ;; Inferring metadata for these would be problematic because:
         ;; * Their intended indents are already intentfully coded in clojure-mode.el
         ;; * The `orchard.indent` logic is not meant to operate on clojure.core stuff,
         ;;   because it compares a given macro against a clojure.core counterpart
         ;;   (which doesn't make sense for a macro which already belongs to clojure.core)
         (not (or (string/starts-with? namespace-name "clojure.")
                  (string/starts-with? namespace-name "cljs.")))
         true)))

(defn- enriched-meta
  "Like `clojure.core/meta` but adds {:fn true} for functions, multimethods and macros,
  and `:style/indent` when missing and inferrable.

  Should only be used for vars."
  [the-var]
  (let [m (meta the-var)]
    (cond-> m
      (or (fn? @the-var)
          (instance? MultiFn @the-var))
      (assoc :fn true)

      (inferrable-indent? m)
      indent/infer-style-indent)))

(defn filter-core-and-get-meta
  "Remove keys whose values are vars in the core namespace."
  [refers]
  (->> refers
       (into {} (keep (fn [[sym the-var]]
                        (when (var? the-var)
                          (let [{the-ns :ns :as the-meta} (enriched-meta the-var)]
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

(defn- remove-redundant-quote
  "Fixes double-quoted arglists coming from the ClojureScript analyzer."
  [{:keys [arglists] :as m}]
  (if (and (sequential? arglists)
           (-> arglists first #{'quote}))
    (assoc m :arglists (second arglists))
    m))

(defn- uses-metadata
  "Creates a var->metadata map for all the `:uses` of a given cljs ns.

  It accomplishes so by querying `all-cljs-namespaces`"
  [all-cljs-namespaces uses]
  (into {}
        (map (fn [[var-name var-ns]]
               (let [defs (some->> all-cljs-namespaces
                                   (filter (fn [x]
                                             (and (map? x)
                                                  (= (:name x)
                                                     var-ns))))
                                   first
                                   :defs)]
                 (if-let [var-meta (some-> defs (get var-name) (get :meta))]
                   [var-name (remove-redundant-quote var-meta)]
                   [var-name {:arglists '([])}]))))
        uses))

(defn- use-macros-metadata
  "Creates a var->metadata map for all the `:use-macros` of a given cljs ns.

  It accomplishes so by querying the JVM Clojure environment."
  [use-macros]
  (into {}
        (map (fn [[var-name var-ns]]
               (if-let [var-ref (resolve (symbol (name var-ns)
                                                 (name var-name)))]
                 [var-name (meta var-ref)]
                 [var-name {:arglists '([]) :macro true}])))
        use-macros))

(defn ns-as-map [object all-objects]
  (cond
    ;; Clojure Namespaces
    (instance? Namespace object)
    {:aliases (misc/update-vals ns-name (ns-aliases object))
     :interns (filter-core-and-get-meta (ns-map object))}

    ;; ClojureScript Namespaces
    (associative? object)
    (let [{:keys [use-macros uses require-macros requires defs]} object
          post-process (fn [result]
                         (misc/update-vals (fn [x]
                                             (cond-> x
                                               (inferrable-indent? x) indent/infer-style-indent
                                               ;; For some reason, cljs (or piggieback) adds a :test key to the
                                               ;; var metadata stored in the namespace.
                                               true (dissoc :test)
                                               true um/relevant-meta))
                                           result))]
      {:aliases (merge require-macros requires)
       :interns (merge (post-process (misc/update-vals cljs-meta-with-fn defs))
                       (post-process (uses-metadata all-objects uses))
                       (post-process (use-macros-metadata use-macros)))})

    :else {}))

(def clojure-core-map
  (when clojure-core
    {:aliases {}
     :interns (->> (ns-map clojure-core)
                   (filter #(var? (second %)))
                   (misc/update-vals #(um/relevant-meta (enriched-meta %))))}))

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
   val-fn
   all-namespaces]
  (->> (vals new-ns-map)
       (map :aliases)
       (mapcat vals)
       (reduce (fn [acc name]
                 (if (or (get acc name)
                         (get old-ns-map name))
                   acc
                   (assoc acc name (ns-as-map (val-fn name)
                                              all-namespaces))))
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
  [old-ns-map project-ns-map cljs all-namespaces]
  (cond
    (and cljs (not (contains? old-ns-map 'cljs.core)))
    (assoc project-ns-map 'cljs.core
           (ns-as-map (cljs-ana/find-ns cljs 'cljs.core)
                      all-namespaces))

    ;; we have cljs and the cljs core, nothing to do
    cljs
    project-ns-map

    ;; we've got core in old or new
    (some #{clojure-core} (mapcat keys [old-ns-map project-ns-map]))
    project-ns-map

    :else
    (assoc project-ns-map clojure-core clojure-core-map)))

(def ^:private jar-namespaces*
  (future
    (into #{}
          (comp (filter misc/jar-file?)
                (map #(JarFile. (io/as-file %)))
                (mapcat ns-find/find-namespaces-in-jarfile))
          (cp/classpath))))

(defn jar-namespaces [x]
  (contains? @jar-namespaces* x))

(defn update-and-send-cache
  "Send a reply to msg with state information assoc'ed.
  old-data is the ns-cache that needs to be updated (the one
  associated with `msg`'s session). Return the updated value for it.
  This function has side-effects (sending the message)!

  Two extra entries are sent in the reply. One is the `:repl-type`,
  which is either `:clj` or `:cljs`.

  The other is `:changed-namespaces`, which is a map from namespace
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
         all-namespaces (if cljs
                          (vals (cljs-ana/all-ns cljs))
                          (all-ns))
         project-ns-map (fast-reduce (fn [acc ns]
                                       (let [name (ns-name-fn ns)]
                                         (if (jar-ns-fn name)
                                           acc ;; Remove all jar namespaces.
                                           (assoc! acc name (ns-as-map ns all-namespaces)))))
                                     all-namespaces)
         project-ns-map (ensure-clojure-core-present old-data
                                                     project-ns-map
                                                     cljs
                                                     all-namespaces)
         changed-ns-map (-> project-ns-map
                            ;; Add back namespaces that the project depends on.
                            (merge-used-aliases (or old-data {})
                                                find-ns-fn
                                                all-namespaces)
                            (calculate-changed-ns-map old-data))]
     (try
       (->> (response-for
             msg :status :state
             :repl-type (if cljs :cljs :clj)
             :changed-namespaces (util/transform-value changed-ns-map))
            (transport-send-fn (:transport msg)))
       ;; We run async, so the connection might have been closed in
       ;; the mean time.
       (catch SocketException _
         nil))
     (merge old-data changed-ns-map))))

;;; Middleware
(defn make-transport
  "Return a Transport that defers to `transport` and possibly notifies
  about the state."
  [{:keys [^Transport transport session] :as msg}]
  (reify Transport
    (recv [_this]
      (.recv transport))

    (recv [_this timeout]
      (.recv transport timeout))

    (send [_this {:keys [status] :as response}]
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
