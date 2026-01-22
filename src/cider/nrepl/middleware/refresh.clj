(ns ^{:clojure.tools.namespace.repl/load false
      :clojure.tools.namespace.repl/unload false} cider.nrepl.middleware.refresh
  ;; The above metadata prevents reloading of this namespace - otherwise,
  ;; `refresh-tracker` is reset with every refresh. This only has any effect
  ;; when developing cider-nrepl itself, or when cider-nrepl is used as a
  ;; checkout dependency - tools.namespace doesn't reload source in JARs.
  (:require
   [cider.nrepl.middleware.util :refer [respond-to]]
   [cider.nrepl.middleware.util.reload :as reload-utils]
   [clojure.main :refer [repl-caught]]
   [clojure.tools.namespace.dir :as dir]
   [clojure.tools.namespace.find :as find]
   [clojure.tools.namespace.reload :as reload]
   [clojure.tools.namespace.track :as track]))

(defonce ^:private refresh-tracker (volatile! (track/tracker)))

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

(defn- reloading-reply
  [{reloading ::track/load}
   msg]
  (respond-to msg :reloading reloading))

(defn- result-reply
  [{error ::reload/error
    error-ns ::reload/error-ns}
   msg]

  (if error
    (reload-utils/error-reply {:error error :error-ns error-ns} msg)
    (respond-to msg :status :ok)))

(defn after-reply
  [{error ::reload/error}
   msg]
  (reload-utils/after-reply error msg))

(def ^{:added "0.48.0"} client-requested-clear?
  "Whether the nREPL client indicated that a `clear` is desired."
  (atom false))

(defn- refresh-reply
  [{:keys [dirs session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (locking refresh-tracker
              (when @client-requested-clear?
                (vreset! refresh-tracker (track/tracker)))
              (vswap! refresh-tracker
                      (fn [tracker]
                        (try
                          (reload-utils/before-reply msg)

                          (-> tracker
                              (dir/scan-dirs (or (seq dirs) (user-refresh-dirs))
                                             (select-keys msg [:platform :add-all?]))
                              (remove-disabled)
                              (doto (reloading-reply msg))
                              (reload/track-reload)
                              (doto (result-reply msg))
                              (doto (after-reply msg)))

                          (catch Throwable e
                            (reload-utils/error-reply {:error e} msg)
                            tracker)

                          (finally
                            (reset! client-requested-clear? false)))))))
          (fn []
            (respond-to msg :status :done)))))

(defn- clear-reply
  [{:keys [session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            ;; This used to be a `locking`-based call to our `clear` implementation.
            ;; Now it merely "enqueues" a clearing,
            ;; because that `locking` could cause unnecessary nREPL timeouts (https://github.com/clojure-emacs/cider/issues/3652 ).
            (reset! client-requested-clear? true))
          (fn []
            (respond-to msg :status :done)))))

(defn handle-refresh [handler msg]
  (case (:op msg)
    ("cider/refresh" "refresh") (refresh-reply (assoc msg :platform find/clj))
    ("cider/refresh-all" "refresh-all") (refresh-reply (assoc msg :platform find/clj :add-all? true))
    ("cider/refresh-clear" "refresh-clear") (clear-reply msg)
    (handler msg)))
