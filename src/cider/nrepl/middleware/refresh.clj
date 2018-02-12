(ns ^{:clojure.tools.namespace.repl/load false
      :clojure.tools.namespace.repl/unload false} cider.nrepl.middleware.refresh
  ;; The above metadata prevents reloading of this namespace - otherwise,
  ;; `refresh-tracker` is reset with every refresh. This only has any effect
  ;; when developing cider-nrepl itself, or when cider-nrepl is used as a
  ;; checkout dependency - tools.namespace doesn't reload source in JARs.
  (:require [cider.nrepl.middleware.pprint :as pprint]
            [cider.nrepl.middleware.stacktrace :refer [analyze-causes]]
            [orchard.misc :as u]
            [clojure.main :refer [repl-caught]]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.find :as find]
            [clojure.tools.namespace.reload :as reload]
            [clojure.tools.namespace.track :as track]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]))

(defonce ^:private refresh-tracker (agent (track/tracker)))

(defn- user-refresh-dirs
  "Directories to watch and reload, as configured by the user.

  See `clojure.tools.namespace.repl/set-refresh-dirs`.

  The var is resolved at runtime to get the \"real\" clojure.tools.namespace,
  not the mranderson-ized version bundled with CIDER. Returns `nil` if c.t.n.r
  isn't loaded. Returns `[]` if c.t.n.r is loaded but no custom dirs have been
  set."
  []
  (some-> (symbol "clojure.tools.namespace.repl" "refresh-dirs")
          resolve
          deref))

;; We construct the keyword at runtime here because namespaced keyword literals
;; in clojure.tools.namespace.repl itself might be rewritten by mranderson - in
;; this case, we still want to disable reloading of namespaces that a user has
;; added the (non-rewritten) metadata to.
(defn- load-disabled?
  [sym]
  (false? (get (meta (find-ns sym))
               (keyword "clojure.tools.namespace.repl" "load"))))

;; As documented in clojure.tools.namespace.repl/disable-reload!,
;; ^{:c.t.n.r/load false} implies ^{:c.t.n.r/unload false}
(defn- unload-disabled?
  [sym]
  (or (load-disabled? sym)
      (false? (get (meta (find-ns sym))
                   (keyword "clojure.tools.namespace.repl" "unload")))))

(defn- remove-disabled
  [tracker]
  (-> tracker
      (update-in [::track/load] #(remove load-disabled? %))
      (update-in [::track/unload] #(remove unload-disabled? %))))

(defn- zero-arity-callable? [func]
  (and (fn? (if (var? func) @func func))
       (->> (:arglists (meta func))
            (some #(or (= [] %) (= '& (first %)))))))

(defn- resolve-and-invoke
  "Takes a string and tries to coerce a function from it. If that
  function is a function of possible zero arity (ie, truly a thunk or
  has optional parameters and can be called with zero args, it is
  called. Returns whether the function was resolved."
  [sym {:keys [session] :as msg}]
  (let [the-var (some-> sym u/as-sym resolve)]

    (when (and (var? the-var)
               (not (zero-arity-callable? the-var)))
      (throw (IllegalArgumentException.
              (format "%s is not a function of no arguments" sym))))

    (binding [*msg* msg
              *out* (get @session #'*out*)
              *err* (get @session #'*err*)]
      (do
        (when (var? the-var)
          (@the-var))
        (var? the-var)))))

(defn- reloading-reply
  [{reloading ::track/load}
   {:keys [transport] :as msg}]
  (transport/send
   transport
   (response-for msg {:reloading reloading})))

(defn- error-reply
  [{:keys [error error-ns]}
   {:keys [pprint-fn session transport] :as msg}]

  (transport/send
   transport
   (response-for msg (cond-> {:status :error}
                       error (assoc :error (analyze-causes error pprint-fn))
                       error-ns (assoc :error-ns error-ns))))

  (binding [*msg* msg
            *err* (get @session #'*err*)]
    (repl-caught error)))

(defn- result-reply
  [{error ::reload/error
    error-ns ::reload/error-ns}
   {:keys [transport] :as msg}]

  (if error
    (error-reply {:error error :error-ns error-ns} msg)
    (transport/send
     transport
     (response-for msg {:status :ok}))))

(defn- before-reply
  [{:keys [before transport] :as msg}]
  (when before
    (transport/send
     transport
     (response-for msg {:status :invoking-before
                        :before before}))

    (let [resolved? (resolve-and-invoke before msg)]

      (transport/send
       transport
       (response-for msg
                     {:status (if resolved?
                                :invoked-before
                                :invoked-not-resolved)
                      :before before})))))

(defn- after-reply
  [{error ::reload/error}
   {:keys [after transport] :as msg}]

  (when (and (not error) after)
    (try
      (transport/send
       transport
       (response-for msg {:status :invoking-after
                          :after after}))

      (let [resolved? (resolve-and-invoke after msg)]

        (transport/send
         transport
         (response-for msg {:status (if resolved?
                                      :invoked-after
                                      :invoked-not-resolved)
                            :after after})))

      (catch Exception e
        (error-reply {:error e} msg)))))

(defn- refresh-reply
  [{:keys [dirs transport] :as msg}]
  (send-off refresh-tracker
            (fn [tracker]
              (try
                (before-reply msg)

                (-> tracker
                    (dir/scan-dirs (or (seq dirs) (user-refresh-dirs))
                                   (select-keys msg [:platform :add-all?]))
                    (remove-disabled)
                    (doto (reloading-reply msg))
                    (reload/track-reload)
                    (doto (result-reply msg))
                    (doto (after-reply msg)))

                (catch Throwable e
                  (error-reply {:error e} msg)
                  tracker)

                (finally
                  (transport/send
                   transport
                   (response-for msg {:status :done})))))))

(defn- clear-reply
  [{:keys [transport] :as msg}]
  (send-off refresh-tracker (constantly (track/tracker)))
  (transport/send
   transport
   (response-for msg {:status :done})))

(defn handle-refresh [handler msg]
  (case (:op msg)
    "refresh" (refresh-reply (assoc msg :platform find/clj))
    "refresh-all" (refresh-reply (assoc msg :platform find/clj :add-all? true))
    "refresh-clear" (clear-reply msg)
    (handler msg)))
