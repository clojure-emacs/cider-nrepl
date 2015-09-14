(ns cider.nrepl.middleware.track-state
  "State tracker for client sessions."
  {:author "Artur Malabarba"}
  (:require [cider.nrepl.middleware.ns :as ns]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :as misc]
            [cljs-tooling.util.analysis :as cljs-ana]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]])
  (:import clojure.tools.nrepl.transport.Transport))

(def clojure-core (try (find-ns 'clojure.core)
                       (catch Exception e nil)))

;;; Auxiliary
(defn update-vals
  "Update the keys of map `m` via the function `f`."
  [f m]
  (reduce (fn [acc [k v]]
            (assoc acc k (f v)))
          {} m))

(defn filter-core
  "Remove keys whose values are vars in the core namespace."
  [refers]
  (reduce (fn [acc [sym var]]
            (if (identical? (:ns (meta var)) clojure-core)
              acc
              (assoc acc sym var)))
          {} refers))

(def relevant-meta-keys
  "Metadata keys that are useful to us.
  This is used so that we don't crowd the ns cache with useless or
  redudant information, such as :name and :ns."
  [:indent :cider-instrumented :macro :arglists :test])

(defn relevant-meta
  "Return the meta of var, selecting only keys of interest."
  [var]
  (->> (select-keys (meta var) relevant-meta-keys)
       (filter second)
       (update-vals pr-str)))

;;; State management
(defmulti ns-as-map
  "Return a map of useful information about ns."
  class)

(defn track-ns? [ns-name-symbol]
  (let [^String name-string (name ns-name-symbol)]
    (not (or (ns/inlined-dependency-name? name-string)
             (.startsWith name-string "cider.")))))

;; Clojure Namespaces
(defmethod ns-as-map clojure.lang.Namespace [ns]
  (let [the-name (ns-name ns)]
    (when (track-ns? the-name)
      {:name    the-name
       :interns (update-vals relevant-meta (ns-interns ns))
       :aliases (update-vals ns-name (ns-aliases ns))
       :refers  (filter-core (ns-refers ns))})))
;; ClojureScript Namespaces
(defmethod ns-as-map clojure.lang.Associative [{the-name :name, :as ns}]
  (when (track-ns? the-name)
    (let [{:keys [use-macros require-macros uses requires defs]} ns]
      {:name    the-name
       ;; For some reason, cljs (or piggieback) adds a :test key to the
       ;; var metadata stored in the namespace.
       :interns (update-vals #(dissoc (relevant-meta %) :test) defs)
       :aliases (merge require-macros requires)
       :refers  (merge uses use-macros)})))

(def ns-cache
  "Cache of the namespace info that has been sent to each session.
  Each key is a session. Each value is a map from namespace names to
  data (as returned by `ns-as-map`)."
  (atom {}))

(defn calculate-changed-ns-map
  "Return a map of namespaces that changed between new and old-map.
  new is a list of namespaces objects, as returned by `all-ns`.
  old-map is a map from namespace names to namespace data, which is
  the same format of map returned by this function. old-map can also
  be nil, which is the same as an empty map."
  [new old-map]
  (reduce (if (empty? old-map)
            ;; Optimization for an empty map.
            (fn [acc ns]
              (assoc acc (:name ns) ns))
            ;; General implementation.
            (fn [acc {:keys [name] :as ns}]
              (if (= (get old-map name) ns)
                acc
                (assoc acc name ns))))
          {}
          (remove not (map ns-as-map new))))

(defn assoc-state
  "Return response with a :state entry assoc'ed.
  This function is not pure nor idempotent!
  It updates the server's cache, so not sending the value it returns
  implies that the client's cache will get outdated.

  The state is a map of two entries. One is the :repl-type, which is
  either :clj or :cljs.

  The other is :changed-namespaces, which is a map from namespace
  names to namespace data (as returned by `ns-as-map`). This contains
  only namespaces which have changed since we last notified the
  client."
  [response {:keys [session] :as msg}]
  (let [old-data (@ns-cache session)
        cljs (cljs/grab-cljs-env msg)
        ;; See what has changed compared to the cache. If the cache
        ;; was empty, everything is considered to have changed (and
        ;; the cache will then be filled).
        changed-ns-map (-> (if cljs
                             (vals (cljs-ana/all-ns cljs))
                             (all-ns))
                           (calculate-changed-ns-map old-data))]
    (swap! ns-cache update-in [session]
           merge changed-ns-map)
    (assoc response :state {:repl-type (if cljs :cljs :clj)
                            :changed-namespaces (misc/transform-value changed-ns-map)})))

;;; Middleware
(defn make-transport
  "Return a Transport that defers to `transport` and possibly notifies
  about the state."
  [{:keys [^Transport transport] :as msg}]
  (reify Transport
    (recv [this] (.recv transport))
    (recv [this timeout] (.recv transport timeout))
    (send [this {:keys [status] :as response}]
      (.send transport (try ;If we screw up, we break eval completely.
                         (cond-> response
                           (contains? status :done) (assoc-state msg))
                         (catch Exception e
                           (println e)
                           response))))))

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
