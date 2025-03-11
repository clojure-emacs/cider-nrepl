(ns cider.nrepl.middleware.reload
  "Reload changed namespaces.
  Alternative to cider.nrepl.middleware.refresh, using clj-reload instead
  of tools.namespace."
  (:require
   [cider.nrepl.middleware.util.reload :as reload-utils]
   [clj-reload.core :as reload]
   [clojure.main :refer [repl-caught]]
   [clojure.string :as str]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.middleware.print :as print]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
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

(defn respond
  [{:keys [transport] :as msg} response]
  (transport/send transport (response-for msg response)))

(defn operation
  [msg]
  (let [opts   {:log-fn (fn [& args]
                          (respond msg {:progress (str/join " " args)}))
                :throw false} ;; mimic the tools.namespace behavior so that we can use `reload-utils/after-reply` uniformly
        reload (user-reload 'reload reload/reload)
        unload (user-reload 'unload reload/unload)]
    (cond
      (:all msg)   (reload (assoc opts :only :all))
      (:clear msg) (unload opts)
      :else        (reload opts))))

(defn- reload-reply
  [{:keys [::print/print-fn transport session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (try
              (reload-utils/before-reply msg)
              (let [{:keys [exception]} (operation msg)]
                (reload-utils/after-reply exception msg)
                (when exception
                  (throw exception))
                (respond msg {:status :ok}))
              (catch Throwable error
                (respond msg {:status :error
                              :error  (stacktrace/analyze error print-fn)})
                (binding [*msg* msg
                          *err* (print/replying-PrintWriter :err msg {})]
                  (repl-caught error)))))

          (fn [] (respond msg {:status :done})))))

(defn handle-reload [handler msg]
  (case (:op msg)
    "cider.clj-reload/reload" (reload-reply msg)
    "cider.clj-reload/reload-all" (reload-reply (assoc msg :all true))
    "cider.clj-reload/reload-clear" (reload-reply (assoc msg :clear true))
    (handler msg)))
