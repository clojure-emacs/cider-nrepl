(ns cider.log.framework.logback
  (:require [cider.log.appender :as appender]
            [clojure.set :as set])
  (:import (ch.qos.logback.classic Level Logger LoggerContext)
           (ch.qos.logback.classic.spi ILoggingEvent LoggingEvent ThrowableProxy)
           (ch.qos.logback.core Appender AppenderBase)
           (org.slf4j LoggerFactory MarkerFactory MDC)))

(def ^:private log-levels
  "The Logback level descriptors."
  (->> [{:name :TRACE
         :category :trace
         :object Level/TRACE}
        {:name :DEBUG
         :category :debug
         :object Level/DEBUG}
        {:name :INFO
         :category :info
         :object Level/INFO}
        {:name :WARN
         :category :warning
         :object Level/WARN}
        {:name :ERROR
         :category :error
         :object Level/ERROR}]
       (map-indexed #(assoc %2 :weight %1))))

(def ^:private level-to-keyword
  (into {} (map (juxt :object :name) log-levels)))

(def ^:private keyword-to-level
  (set/map-invert level-to-keyword))

(defn- logger-context
  "Return the Logback logger context."
  ^LoggerContext []
  (LoggerFactory/getILoggerFactory))

(defn- get-logger
  "Return the logger by `name` from the logger `context`."
  ^Logger [^String name]
  (.getLogger (logger-context) name))

(defn- event-exception [^LoggingEvent event]
  (let [proxy (.getThrowableProxy event)]
    (when (instance? ThrowableProxy proxy)
      (.getThrowable ^ThrowableProxy proxy))))

(defn- event-data [^LoggingEvent event]
  (let [exception (event-exception event)]
    (cond-> {:arguments (vec (.getArgumentArray event))
             :id (java.util.UUID/randomUUID)
             :level (level-to-keyword (.getLevel event))
             :logger (.getLoggerName event)
             :mdc (into {} (.getMDCPropertyMap event))
             :message (.getFormattedMessage event)
             :thread (.getThreadName event)
             :timestamp (.getTimeStamp event)}
      exception (assoc :exception exception))))

(defn- add-appender
  "Attach the Logback appender."
  [framework appender]
  (let [instance (doto (proxy [AppenderBase] []
                         (append [^ILoggingEvent event]
                           (swap! appender appender/add-event (event-data event))))
                   (.setContext (logger-context))
                   (.setName (:id @appender))
                   (.start))]
    (swap! appender assoc :instance instance)
    (doto ^Logger (get-logger (or (:logger appender) (:root-logger framework)))
      (.addAppender instance))
    framework))

(defn- level-int [level]
  (some-> level keyword-to-level Level/toLocationAwareLoggerInteger))

(defn- log [framework {:keys [arguments exception level logger marker mdc message]}]
  (let [logger (get-logger (or logger (:root-logger framework)))]
    (doseq [[key value] (seq mdc)]
      (MDC/put key value))
    (.log logger
          (some-> marker MarkerFactory/getMarker)
          ^String (.getName logger) ;;TODO: What is "fqcn"?
          (level-int (or level :INFO))
          message
          (into-array Object arguments)
          exception)
    (when (seq mdc)
      (MDC/clear))))

(defn- remove-appender
  "Remove `appender` from the Logback `framework`."
  [framework appender]
  (.stop ^Appender (:instance @appender))
  (doto ^Logger (get-logger (or (:logger appender) (:root-logger framework)))
    (.detachAppender ^String (:id @appender)))
  framework)

(def framework
  "The Logback logging framework."
  {:add-appender-fn #'add-appender
   :id "logback"
   :javadoc-url "https://logback.qos.ch/apidocs"
   :levels log-levels
   :log-fn #'log
   :name "Logback"
   :remove-appender-fn #'remove-appender
   :root-logger Logger/ROOT_LOGGER_NAME
   :website-url "https://logback.qos.ch"})
