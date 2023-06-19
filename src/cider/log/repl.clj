(ns ^{:clojure.tools.namespace.repl/unload false
      :clojure.tools.namespace.repl/load false}
 cider.log.repl
  "This namespace provide functionality to capture and search log events
  emitted by Java logging frameworks. It is designed to be used by
  non-NREPL users in a plain REPL.

  Example usage:

  ;; Use the :logback logging framework
  (repl/set-framework! :logback)

  ;; Add an appender to the log framework that captures events.
  (repl/add-appender)

  ;; Log something
  (repl/log {:message \"hello\"})

  ;; Return all captured log events
  (repl/events)

  ;; Search log events by message
  (repl/events {:pattern \"hell.*\"})

  ;; Search log events by level
  (repl/events {:level :INFO})

  ;; Add a log consumer that prints log events
  (repl/add-consumer
   {:callback (fn [_consumer event]
                (clojure.pprint/pprint event))})

  ;; Log something else
  (repl/log {:message \"world\"})

  ;; Remove all consumers and appenders
  (repl/shutdown)"
  (:require [cider.log.framework :as framework]
            [clojure.pprint :as pprint]))

(defonce ^:dynamic *settings*
  {:framework "jul"
   :appender "cider-log"
   :consumer "cider-log"})

(defonce ^:dynamic *frameworks*
  (framework/resolve-frameworks))

(defn- merge-default [options]
  (let [options (merge *settings* options)]
    (cond-> options
      (contains? options :appender)
      (update :appender name)
      (contains? options :consumer)
      (update :consumer name)
      (contains? options :framework)
      (update :framework name))))

(defn- appender-options [options]
  (let [{:keys [appender filters logger size threshold]} (merge-default options)]
    (cond-> {:id appender}
      filters (assoc :filters filters)
      logger (assoc :logger logger)
      size (assoc :size size)
      threshold (assoc :threshold threshold))))

(defn- consumer-options [options]
  (let [{:keys [callback consumer filters]} (merge-default options)]
    (cond-> {:callback (or callback (fn [_ event] (pprint/pprint event)))}
      consumer (assoc :id consumer)
      filters (assoc :filters filters))))

(defn- criteria
  [{:keys [end-time exceptions level loggers pattern start-time threads]}]
  {:filters (cond-> {}
              (seq exceptions) (assoc :exceptions exceptions)
              (seq loggers) (assoc :loggers loggers)
              end-time (assoc :end-time end-time)
              level (assoc :level level)
              pattern (assoc :pattern pattern)
              start-time (assoc :start-time start-time)
              threads (assoc :threads threads))})

(defn update-settings!
  "Update the `*settings*` by applying `f` and `args` to it."
  [f & args]
  (alter-var-root #'*settings* #(apply f % args))
  *settings*)

;; Log Framework

(defn- ensure-framework
  "Ensure that the :framework in `options` is valid. Returns the
  framework or throws an exception."
  [options]
  (let [{:keys [framework]} (merge-default options)]
    (or (get *frameworks* (some-> framework name))
        (throw (ex-info (str "Unsupported log framework: " framework)
                        {:framework framework
                         :supported-frameworks (keys *frameworks*)})))))

(defn framework
  "Lookup the current log framework or the one in `options`.

  Options:
    :framework - The identifier of the framework."
  [& {:as options}]
  (let [{:keys [framework]} (merge-default options)]
    (get *frameworks* (some-> framework name))))

(defn- swap-framework! [options f & args]
  (let [framework (ensure-framework options)]
    (alter-var-root #'*frameworks*
                    (fn [frameworks]
                      (update frameworks (:id framework) #(apply f % args))))
    (get *frameworks* (:id framework))))

(defn set-framework!
  "Change the current framework to `identifier`."
  [identifier]
  (update-settings! assoc :framework (name identifier)))

(defn shutdown
  "Remove all consumers and appenders of a framework.

  Options:
    :framework - The identifier of the framework."
  [& {:as options}]
  (let [options (merge-default options)]
    (swap-framework! options framework/shutdown)))

;; Log Appender

(defn- ensure-appender
  "Ensure that the :appender in `options` is registered. Returns the
  registered appender or throws an exception."
  [options]
  (let [{:keys [appender]} (merge-default options)
        framework (ensure-framework options)]
    (or (framework/appender framework {:id appender})
        (throw (ex-info (str "Log appender not registered: " appender)
                        {:appender appender
                         :registered-appenders (->> (framework/appenders framework)
                                                    (map (comp :id deref)))})))))

(defn appenders
  "Return all appenders of a log framework.

  Options:
    :framework - The identifier of the framework."
  [& {:as options}]
  (framework/appenders (ensure-framework options)))

(defn appender
  "Return the appender of a log framework.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender."
  [& {:as options}]
  (let [{:keys [appender]} (merge-default options)]
    (framework/appender (ensure-framework options) {:id appender})))

(defn add-appender
  "Add a log appender to a framework.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender.
    :filters - A map of criteria used to filter log events.
    :logger - The name of the logger to which the append will be attached.
    :size - The number of events to capture in the appender.
    :threshold - A threshold in percentage used to garbage collect log events."
  [& {:as options}]
  (let [options (merge-default options)]
    (-> (swap-framework! options framework/add-appender (appender-options options))
        (framework/appender {:id (:appender options)}))))

(defn clear-appender
  "Clear the events captured by and appender of a framework.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender."
  [& {:as options}]
  (swap-framework! options framework/clear-appender @(ensure-appender options)))

(defn remove-appender
  "Remove an appender from a framework.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender."
  [& {:as options}]
  (swap-framework! options framework/remove-appender @(ensure-appender options)))

(defn set-appender!
  "Change the current appender to `identifier`."
  [identifier]
  (update-settings! assoc :appender (name identifier)))

(defn update-appender
  "Update the appender of a framework.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender.
    :filters - A map of criteria used to filter log events.
    :logger - The name of the logger to which the append will be attached.
    :size - The number of events to capture in the appender.
    :threshold - A threshold in percentage used to garbage collect log events."
  [& {:as options}]
  (ensure-appender options)
  (swap-framework! options framework/update-appender (appender-options options)))

;; Log Consumer

(defn add-consumer
  "Add a log consumer to a framework.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender.
    :consumer - The identifier of the consumer.
    :callback - A function that will be called for each log event.
    :filters - A map of criteria used to filter log events."
  [& {:as options}]
  (swap-framework! options framework/add-consumer
                   @(ensure-appender options)
                   (consumer-options options)))

(defn remove-consumer
  "Remove a consumer from an appender.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender.
    :consumer - The identifier of the consumer."
  [& {:as options}]
  (let [{:keys [consumer]} (merge-default options)]
    (swap-framework! options framework/remove-consumer
                     @(ensure-appender options)
                     {:id consumer})))

(defn update-consumer
  "Update the consumer of an appender.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender.
    :consumer - The identifier of the consumer.
    :filters - A map of criteria used to filter log events."
  [& {:as options}]
  (let [options (merge-default options)]
    (swap-framework! options framework/update-consumer
                     @(ensure-appender options)
                     (consumer-options options))))

(defn set-consumer!
  "Change the current consumer to `identifier`."
  [identifier]
  (update-settings! assoc :consumer (name identifier)))

;; Log Event

(defn event
  "Find a log event captured by the an appender of a framework.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender.
    :event - The identifier of the event."
  [& {:as options}]
  (when-let [event-id (:event options)]
    (framework/event (ensure-framework options)
                     @(ensure-appender options)
                     event-id)))

(defn events
  "Search log events captured by an appender of a framework.

  Options:
    :framework - The identifier of the framework.
    :appender - The identifier of the appender.
    :end-time - Only include events before this timestamp.
    :exceptions - Only include events matching the exception classes.
    :level - Only include events with the given level.
    :loggers - Only include events emitted by the loggers.
    :pattern - Only include events whose message matches the regex pattern.
    :start-time - Only include events after this timestamp.
    :threads - Only include events emitted by the given threads."
  [& {:as options}]
  (framework/search-events (ensure-framework options)
                           @(ensure-appender options)
                           (criteria options)))

(defn log
  "Emit a log event.

  Options:
    :framework - The identifier of the framework.
    :message - The message of the log event.
    :mdc - The mapped diagnostic context of the log event.
    :arguments - The arguments of the log event.
    :level - The level of the log event.
    :logger - The logger used to emit the log event."
  [& {:as options}]
  (let [options (merge-default options)]
    (framework/log (ensure-framework options)
                   (let [{:keys [arguments level logger message mdc]} options]
                     (cond-> {}
                       arguments (assoc :arguments arguments)
                       level (assoc :level level)
                       logger (assoc :logger logger)
                       mdc (assoc :mdc mdc)
                       message (assoc :message message))))))
