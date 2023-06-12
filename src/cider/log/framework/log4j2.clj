(ns cider.log.framework.log4j2
  (:require [cider.log.appender :as appender]
            [clojure.set :as set])
  (:import (org.apache.logging.log4j Level MarkerManager ThreadContext)
           (org.apache.logging.log4j.core LogEvent LoggerContext)
           (org.apache.logging.log4j.core.appender AbstractAppender)
           (org.apache.logging.log4j.core.config AppenderRef LoggerConfig$Builder Property)
           (org.apache.logging.log4j.core.impl ThrowableProxy)
           (org.apache.logging.log4j.message MessageFormatMessage)))

(def ^:private log-levels
  "The Log4j2 level descriptors."
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
         :object Level/ERROR}
        {:name :FATAL
         :category :error
         :object Level/FATAL}]
       (map-indexed #(assoc %2 :weight %1))))

(def ^:private level-to-keyword
  (into {} (map (juxt :object :name) log-levels)))

(def ^:private keyword-to-level
  (set/map-invert level-to-keyword))

(defn- event-exception [^LogEvent event]
  (let [proxy (.getThrownProxy event)]
    (when (instance? ThrowableProxy proxy)
      (.getThrowable ^ThrowableProxy proxy))))

(defn- event-data [^LogEvent event]
  (let [exception (event-exception event)]
    (cond-> {:arguments (-> event .getMessage .getParameters vec)
             :id (java.util.UUID/randomUUID)
             :level (level-to-keyword (.getLevel event))
             :logger (.getLoggerName event)
             :mdc (some->> (.getContextData event) .toMap (into {}))
             :message (some-> event .getMessage .getFormattedMessage)
             :thread (.getThreadName event)
             :timestamp (.getTimeMillis event)}
      exception (assoc :exception exception))))

(defn- add-appender [framework appender]
  (let [context (LoggerContext/getContext false)
        config (.getConfiguration context)]
    (locking config
      (let [^String logger-name (or (:logger @appender) (:root-logger framework))
            logger-config (.getLoggerConfig config logger-name)
            logger-config' (-> (LoggerConfig$Builder.)
                               (.withAdditivity (.isAdditive logger-config))
                               (.withConfig config)
                               (.withIncludeLocation (str (.isIncludeLocation logger-config)))
                               (.withLevel ^Level (keyword-to-level (:level @appender) Level/INFO))
                               (.withLoggerName logger-name)
                               (.withProperties (.getPropertyArray logger-config))
                               (.withRefs (into-array
                                           (conj (seq (.getAppenderRefs logger-config))
                                                 (AppenderRef/createAppenderRef (:id @appender) nil nil))))
                               (.withtFilter (.getFilter logger-config))
                               (.build))
            instance (doto (proxy [AbstractAppender] [(:id @appender) nil nil true (into-array Property [])]
                             (append [^LogEvent event]
                               (swap! appender appender/add-event (event-data event))))
                       (.start))]
        (swap! appender assoc :instance instance)
        (.addAppender logger-config' instance nil nil)
        (.addLogger config logger-name logger-config')
        (.updateLoggers context)
        framework))))

(defn- remove-appender
  "Remove `appender` from the Log4j `framework`."
  [framework appender]
  (let [context (LoggerContext/getContext false)
        config (.getConfiguration context)
        ^String logger-name (or (:logger appender) (:root-logger framework))
        logger-config (.getLoggerConfig config logger-name)]
    (.removeAppender logger-config (:id @appender))
    (.setConfiguration context config)
    (.updateLoggers context)
    framework))

(defn- log [framework {:keys [arguments exception level logger marker mdc message]}]
  (let [context (LoggerContext/getContext false)
        ^String logger-name (or logger (:root-logger framework))]
    (doseq [[key value] (seq mdc)]
      (ThreadContext/put key value))
    (.log (.getLogger context logger-name)
          ^Level (keyword-to-level level Level/INFO)
          (some-> marker MarkerManager/getMarker)
          (MessageFormatMessage. ^String message (into-array Object arguments))
          ^Throwable exception)
    (when (seq mdc)
      (ThreadContext/clearAll))))

(def framework
  "The Log4j2 logging framework."
  {:add-appender-fn #'add-appender
   :id "log4j2"
   :javadoc-url "https://logging.apache.org/log4j/2.x/javadoc/log4j-api"
   :levels log-levels
   :log-fn #'log
   :name "Log4j2"
   :remove-appender-fn #'remove-appender
   :root-logger (-> (LoggerContext/getContext false) .getRootLogger .getName)
   :website-url "https://logging.apache.org"})
