(ns cider.nrepl.middleware.reload
  "Reload changed namespaces.
  Alternative to cider.nrepl.middleware.refresh, using clj-reload instead
  of tools.namespace."
  (:require
   [cider.nrepl.middleware.util.reload :as reload-utils]
   [cider.nrepl.middleware.util :refer [respond-to]]
   [clj-reload.core :as reload]
   [clojure.main :refer [repl-caught]]
   [clojure.string :as str]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.middleware.print :as print]
   [orchard.stacktrace :as stacktrace]))

(defn- user-reload
  "Resolve clj-reload.core/<sym> from the user project or return fallback."
  [sym fallback]
  (or (some-> (symbol "clj-reload.core" (str sym)) ;; Don't use mrandorsenized version
              resolve)
      fallback))

(defn- init
  "Initialize clj-reload with dirs.
  Only used for test, but necessary because of mranderson."
  [dirs]
  (reload/init {:dirs dirs}))

(defn operation
  [msg]
  (let [opts   {:log-fn (fn [& args]
                          (respond-to msg {:progress (str/join " " args)}))
                :throw false} ;; mimic the tools.namespace behavior so that we can use `reload-utils/after-reply` uniformly
        reload (user-reload 'reload reload/reload)
        unload (user-reload 'unload reload/unload)]
    (cond
      (:all msg)   (reload (assoc opts :only :all))
      (:clear msg) (unload opts)
      :else        (reload opts))))

(defn- reload-reply
  [{:keys [session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (try
              (reload-utils/before-reply msg)
              (let [{:keys [exception]} (operation msg)]
                (reload-utils/after-reply exception msg)
                (when exception
                  (throw exception))
                (respond-to msg {:status :ok}))
              (catch Throwable error
                (respond-to msg {:status :error
                                 :error  (stacktrace/analyze error)})
                (binding [*msg* msg
                          *err* (print/replying-PrintWriter :err msg {})]
                  (repl-caught error)))))

          (fn [] (respond-to msg {:status :done})))))

(defn handle-reload [handler msg]
  (case (:op msg)
    "cider.clj-reload/reload" (reload-reply msg)
    "cider.clj-reload/reload-all" (reload-reply (assoc msg :all true))
    "cider.clj-reload/reload-clear" (reload-reply (assoc msg :clear true))
    (handler msg)))
