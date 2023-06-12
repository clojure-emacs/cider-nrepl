(ns cider.nrepl.middleware.log
  (:require [cider.log.event :as event]
            [cider.log.framework :as framework]
            [cider.nrepl.middleware.inspect :as middleware.inspect]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [haystack.analyzer :as analyzer]
            [haystack.parser.clojure.throwable :as throwable]
            [nrepl.middleware.print :as print]
            [nrepl.misc :refer [response-for]]
            [nrepl.transport :as transport]
            [orchard.inspect :as orchard.inspect])
  (:import (java.io StringWriter)
           (java.util UUID)))

(defn- select-consumer
  "Return the log `consumer` in a Bencode compatible format."
  [consumer]
  (-> (select-keys consumer [:id :filters])
      (update :id str)))

(defn- select-appender
  "Return the log `appender` in a Bencode compatible format."
  [appender]
  (-> (select-keys appender [:filters :logger :id :size :threshold])
      (assoc :consumers (map select-consumer (vals (:consumers appender))))))

(defn- select-framework
  "Return the log `frameowrk` in a Bencode compatible format."
  [framework]
  (-> (select-keys framework [:id :javadoc-url :name :root-logger :website-url])
      (assoc :appenders (map (comp select-appender deref)
                             (framework/appenders framework)))
      (assoc :levels (map #(select-keys % [:name :category :weight]) (:levels framework)))))

(defn- select-exception
  "Return the `exception` in a Bencode compatible format."
  [exception]
  (let [exception-map (throwable/Throwable->map exception)
        strip-cause #(dissoc % :data :trace)]
    (cond-> (strip-cause exception-map)
      (seq (:via exception-map))
      (update :via #(map strip-cause %)))))

(defn- select-event
  "Return the log `event` in a Bencode compatible format."
  [{:keys [exception id] :as event}]
  (cond-> (select-keys event [:exception :level :logger :message :id :thread :timestamp])
    (uuid? id)
    (update :id str)
    (instance? Throwable exception)
    (update :exception select-exception)))

;; TODO: Double check this! Sometimes inspecting a log event works only after
;; inspecting something else with the Cider inspector.
(defn- inspect-value
  "Show `value` in the Cider inspector"
  [{:keys [page-size max-atom-length max-coll-size] :as msg} value]
  (let [inspector (middleware.inspect/swap-inspector!
                   msg #(-> (assoc % :page-size (or page-size 32)
                                   :indentation 0
                                   :max-atom-length max-atom-length
                                   :max-coll-size max-coll-size)
                            (orchard.inspect/start value)))]
    (#'middleware.inspect/inspector-response msg inspector)))

(defn- framework
  "Lookup the framework from the :framework key of the NREPL message."
  [{:keys [session framework]}]
  (or (get-in (meta session) [::frameworks framework])
      (throw (ex-info "Log framework not found"
                      {:error :log-framework-not-found
                       :framework framework}))))

(defn- filters
  "Extract the filters from an NREPL dictinary."
  [{:keys [end-time exceptions level loggers pattern start-time threads]}]
  (cond-> {}
    (nat-int? end-time)
    (assoc :end-time end-time)
    (and (seq exceptions) (every? string? exceptions))
    (assoc :exceptions exceptions)
    (or (string? level) (keyword? level))
    (assoc :level (keyword level))
    (and (seq loggers) (every? string? loggers))
    (assoc :loggers loggers)
    (string? pattern)
    (assoc :pattern pattern)
    (nat-int? start-time)
    (assoc :start-time start-time)
    (and (seq threads) (every? string? threads))
    (assoc :threads threads)))

(defn- appender
  "Make an appender map from the :appender, :filters, :size
  and :threshold keys of the NREPL message."
  [{:keys [appender logger size threshold] :as msg}]
  (when (string? appender)
    (cond-> {:id appender}
      (map? (:filters msg))
      (assoc :filters (filters (:filters msg)))
      (string? logger)
      (assoc :logger logger)
      (pos-int? size)
      (assoc :size size)
      (nat-int? threshold)
      (assoc :threshold threshold))))

(defn- consumer
  "Make a consumer map from the :consumer and :filters keys of the NREPL message."
  [{:keys [consumer] :as msg}]
  (when (string? consumer)
    (cond-> {:id consumer}
      (map? (:filters msg))
      (assoc :filters (filters (:filters msg))))))

(defn- event
  "Lookup the log event from the :framework, :appender and :event
  keys of the NREPL `msg`."
  [{:keys [event] :as msg}]
  (or (framework/event (framework msg) (appender msg) (UUID/fromString event))
      (throw (ex-info "Log event not found"
                      {:error :log-event-not-found
                       :framework (:framework msg)
                       :appender (:appender msg)
                       :event event}))))

(defn swap-framework!
  "Swap the framework bound in the session by applying `f` with `args`."
  [msg f & args]
  (if-let [framework (framework msg)]
    (-> (:session msg)
        (alter-meta! update-in [::frameworks (:id framework)] #(apply f % args))
        (get-in [::frameworks (:id framework)]))
    (throw (ex-info "Log framework not found"
                    {:type :log-framework-not-found
                     :framework (:framework msg)}))))

(defn add-appender-reply
  "Add an appender to a log framework."
  [msg]
  (let [appender (appender msg)]
    {:log-add-appender
     (-> (swap-framework! msg framework/add-appender appender)
         (framework/appender appender)
         (deref)
         (select-appender))}))

(defn add-consumer-reply
  "Add a consumer to an appender of a log framework."
  [{:keys [consumer filters transport] :as msg}]
  (let [appender (appender msg)
        consumer {:id (or consumer (str (UUID/randomUUID)))
                  :filters (or filters {})
                  :callback (fn [consumer event]
                              (->> (response-for msg
                                                 :log-consumer (str (:id consumer))
                                                 :log-event (select-event event)
                                                 :status :log-event)
                                   (transport/send transport)))}]
    {:log-add-consumer
     (-> (swap-framework! msg framework/add-consumer appender consumer)
         (framework/consumer appender consumer)
         (select-consumer))}))

(defn clear-appender-reply
  "Clear all events of a log appender."
  [msg]
  (let [appender (appender msg)]
    {:log-clear-appender
     (-> (swap-framework! msg framework/clear-appender appender)
         (framework/appender appender)
         (deref)
         (select-appender))}))

(defn analyze-stacktrace-reply
  "Show the stacktrace of a log event in the debugger."
  [{:keys [transport ::print/print-fn] :as msg}]
  (let [event (event msg)]
    (if-let [exception (:exception event)]
      (do (doseq [cause (analyzer/analyze exception print-fn)]
            (transport/send transport (response-for msg cause)))
          (transport/send transport (response-for msg :status :done)))
      (transport/send transport (response-for msg :status :no-error)))))

(defn exceptions-reply
  "Return the exceptions and their frequencies for the given framework and appender."
  [msg]
  {:log-exceptions (->> (framework/events (framework msg) (appender msg))
                        (event/exception-frequencies))})

(defn frameworks-reply
  "Return the available log frameworks."
  [{:keys [session]}]
  {:log-frameworks (->> (meta session)
                        ::frameworks vals
                        (map select-framework))})

(defn format-event-reply
  "Format a log event."
  [{:keys [::print/print-fn] :as msg}]
  (let [event (event msg)
        writer (StringWriter.)]
    (print-fn event writer)
    {:log-format-event (str writer)}))

(defn inspect-event-reply
  "Inspect a log event."
  [msg]
  (inspect-value msg (event msg)))

(defn levels-reply
  "Return the log levels and their frequencies for the given framework and appender."
  [msg]
  {:log-levels (->> (framework/events (framework msg) (appender msg))
                    (event/level-frequencies))})

(defn loggers-reply
  "Return the loggers and their frequencies for the given framework and appender."
  [msg]
  {:log-loggers (->> (framework/events (framework msg) (appender msg))
                     (event/logger-frequencies))})

(defn remove-appender-reply
  "Remove an appender from a log framework."
  [msg]
  (let [appender (appender msg)]
    (swap-framework! msg framework/remove-appender appender)
    {:log-remove-appender {:id (str (:id appender))}}))

(defn remove-consumer-reply
  "Remove a consumer from the appender of a log framework."
  [msg]
  (let [appender (appender msg)
        consumer (consumer msg)]
    (swap-framework! msg framework/remove-consumer appender consumer)
    {:log-remove-consumer (select-consumer consumer)}))

(defn update-appender-reply
  "Update the appender of a log framework."
  [msg]
  (let [appender (appender msg)]
    {:log-update-appender
     (-> (swap-framework! msg framework/update-appender appender)
         (framework/appender appender)
         (deref)
         (select-appender))}))

(defn update-consumer-reply
  "Update the consumer of a log appender."
  [msg]
  (let [appender (appender msg)
        consumer (consumer msg)]
    {:log-update-consumer
     (-> (swap-framework! msg framework/update-consumer appender consumer)
         (framework/consumer appender consumer)
         (select-consumer))}))

(defn search-reply
  "Search the log events of an appender."
  [msg]
  {:log-search
   (->> (select-keys msg [:filters :limit :offset])
        (framework/search-events (framework msg) (appender msg))
        (map select-event))})

(defn threads-reply
  "Return the threads and their frequencies for the given framework and appender."
  [msg]
  {:log-threads (->> (framework/events (framework msg) (appender msg))
                     (event/thread-frequencies))})

(defn handle-log
  "Handle NREPL log operations."
  [handler {:keys [session] :as msg}]
  (when-not (contains? (meta session) ::frameworks)
    (alter-meta! session assoc ::frameworks (framework/resolve-frameworks)))
  (with-safe-transport handler msg
    "log-add-appender" add-appender-reply
    "log-add-consumer" add-consumer-reply
    "log-analyze-stacktrace" analyze-stacktrace-reply
    "log-clear-appender" clear-appender-reply
    "log-exceptions" exceptions-reply
    "log-format-event" format-event-reply
    "log-frameworks" frameworks-reply
    "log-inspect-event" inspect-event-reply
    "log-levels" levels-reply
    "log-loggers" loggers-reply
    "log-remove-appender" remove-appender-reply
    "log-remove-consumer" remove-consumer-reply
    "log-search" search-reply
    "log-update-appender" update-appender-reply
    "log-update-consumer" update-consumer-reply
    "log-threads" threads-reply))
