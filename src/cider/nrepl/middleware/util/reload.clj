(ns cider.nrepl.middleware.util.reload
  "Common parts for the code-reloading middleware namespaces."
  {:added "0.47.0"}
  (:require
   [cider.nrepl.middleware.util :refer [respond-to]]
   [cider.nrepl.middleware.util.nrepl :as util.nrepl]
   [clojure.main :refer [repl-caught]]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.middleware.print :as print]
   [orchard.misc :as misc]
   [orchard.stacktrace :as stacktrace])
  (:import
   (clojure.lang Namespace Var)))

(defn error-reply [{:keys [error error-ns]} msg]
  (respond-to msg (cond-> {:status :error}
                    error (assoc :error (stacktrace/analyze error))
                    error-ns (assoc :error-ns error-ns)))

  (binding [*msg* msg
            *err* (print/replying-PrintWriter :err msg {})]
    (repl-caught error)))

(defn- zero-arity-callable? [func]
  (and (fn? (if (var? func) @func func))
       (->> (:arglists (meta func))
            (some #(or (= [] %) (= '& (first %)))))))

(defn- resolve-and-invoke
  "Takes a string and tries to coerce a function from it. If that
  function is a function of possible zero arity (ie, truly a thunk or
  has optional parameters and can be called with zero args, it is
  called. Returns whether the function was resolved."
  [sym {:keys [_session] :as msg}]
  (let [the-var (some-> sym misc/as-sym resolve)]

    (when (and (var? the-var)
               (not (zero-arity-callable? the-var)))
      (throw (IllegalArgumentException.
              (format "%s is not a function of no arguments" sym))))

    (binding [*msg* msg
              *out* (print/replying-PrintWriter :out msg {})
              *err* (print/replying-PrintWriter :err msg {})]
      (when (var? the-var)
        (@the-var))
      (var? the-var))))

(defn before-reply [{:keys [before] :as msg}]
  (when before
    (respond-to msg {:status :invoking-before
                     :before before})

    (let [resolved? (resolve-and-invoke before msg)]
      (respond-to msg {:status (if resolved?
                                 :invoked-before
                                 :invoked-not-resolved)
                       :before before}))))

(defn- remade
  "The namespace called what ns is called, when a reload made it anew. nil
  for no ns, as an anonymous var from `Var/create` has."
  ^Namespace [ns]
  (when-let [current (some-> ns ns-name find-ns)]
    (when-not (identical? ns current)
      current)))

(defn relink
  "Points the aliases and refers on namespaces a reload made anew with
  `remove-ns` at the new ones, and their vars by the same names, which the
  namespaces it left alone, like the REPL's, would keep on the old ones.
  What has nothing new to point at stays as it was. Refers go by the var's
  own namespace and name, not its metadata, which potemkin's `import-vars`
  copies from another var."
  []
  (doseq [^Namespace n (all-ns)]
    (doseq [[alias-sym target] (.getAliases n)
            :let [current (remade target)]
            :when current]
      (.removeAlias n alias-sym)
      (.addAlias n alias-sym current))
    (doseq [[sym v] (.getMappings n)
            :when (var? v)
            :let [^Var fresh (some-> (remade (.ns ^Var v)) (.findInternedVar (.sym ^Var v)))]
            :when (and fresh (.isPublic fresh))]
      (.unmap n sym)
      (.refer n sym fresh))))

(def ^:private nrepl-1-3+?
  (util.nrepl/satisfies-version? 1 3))

(defn- reenter
  "Puts the session in the namespace it was in, when a reload made that anew,
  as `in-ns` by its name would. Since nREPL 1.3, an op runs with the session's
  bindings and keeps them after it, so it's the binding that changes. Before,
  only what's in the session counts."
  [session]
  (if nrepl-1-3+?
    (when-let [current (remade *ns*)]
      (set! *ns* current))
    (when-let [current (remade (get @session #'*ns*))]
      (swap! session assoc #'*ns* current))))

(defn after-reply [error
                   {:keys [after session] :as msg}]
  (when-not error
    (try
      (relink)
      (reenter session)
      (when after
        (respond-to msg {:status :invoking-after
                         :after after})

        (let [resolved? (resolve-and-invoke after msg)]
          (respond-to msg {:status (if resolved?
                                     :invoked-after
                                     :invoked-not-resolved)
                           :after after})))

      (catch Exception e
        (error-reply {:error e} msg)))))
