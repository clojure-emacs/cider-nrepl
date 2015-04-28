(ns cider.nrepl.middleware.refresh
  (:require [cider.nrepl.middleware.stacktrace :refer [analyze-causes]]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.reload :as reload]
            [clojure.tools.namespace.repl :as repl]
            [clojure.tools.namespace.track :as track]))

(defonce ^:private refresh-tracker (atom (track/tracker)))

(defn- scan
  [tracker scan-fn dirs]
  (apply scan-fn tracker (or (seq dirs) [])))

(defn- remove-disabled
  [tracker]
  (#'repl/remove-disabled tracker))

(defn- reloading-reply
  [tracker {:keys [transport] :as msg}]
  (transport/send
   transport
   (response-for msg :reloading (::track/load tracker))))

(defn- result-reply
  [tracker {:keys [print-length print-level transport] :as msg}]
  (transport/send
   transport
   (response-for msg (if-let [error (::reload/error tracker)]
                       {:status #{:error :done}
                        :error (analyze-causes error print-length print-level)
                        :error-ns (::reload/error-ns tracker)}
                       {:status #{:ok :done}}))))

(defn- refresh-reply
  [{:keys [dirs scan-fn] :as msg}]
  (reset! refresh-tracker
          (-> @refresh-tracker
              (scan scan-fn dirs)
              remove-disabled
              (doto (reloading-reply msg))
              reload/track-reload
              (doto (result-reply msg)))))

(defn wrap-refresh
  "Middleware that provides code reloading."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "refresh" (refresh-reply (assoc msg :scan-fn dir/scan))
      "refresh-all" (refresh-reply (assoc msg :scan-fn dir/scan-all))
      (handler msg))))

(set-descriptor!
 #'wrap-refresh
 {:handles
  {"refresh"
   {:doc "Reloads all changed files in dependency order."
    :optional {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."
               "print-length" "Value to bind to `*print-length*` when pretty-printing error data, if an exception is thrown."
               "print-level" "Value to bind to `*print-level*` when pretty-printing error data, if an exception is thrown."}
    :returns {"reloading" "List of namespaces that will be reloaded."
              "status" "`:ok` if reloading was successful; otherwise `:error`."
              "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
              "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."}}
   "refresh-all"
   {:doc "Reloads all files in dependency order."
    :optional {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."
               "print-length" "Value to bind to `*print-length*` when pretty-printing error data, if an exception is thrown."
               "print-level" "Value to bind to `*print-level*` when pretty-printing error data, if an exception is thrown."}
    :returns {"reloading" "List of namespaces that will be reloaded."
              "status" "`:ok` if reloading was successful; otherwise `:error`."
              "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
              "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."}}}})
