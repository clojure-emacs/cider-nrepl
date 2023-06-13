(ns cider.log.framework.jul
  "Log event capturing implementation for Java Util Logging."
  {:author "r0man"}
  (:require [cider.log.appender :as appender]
            [clojure.set :as set])
  (:import (java.util.logging Level Logger LogRecord StreamHandler)))

(def ^:private log-levels
  "The Java Util Logging level descriptors."
  (->> [{:name :FINEST
         :category :trace
         :object Level/FINEST}
        {:name :FINER
         :category :trace
         :object Level/FINER}
        {:name :FINE
         :category :debug
         :object Level/FINE}
        {:name :CONFIG
         :category :info
         :object Level/CONFIG}
        {:name :INFO
         :category :info
         :object Level/INFO}
        {:name :WARNING
         :category :warning
         :object Level/WARNING}
        {:name :SEVERE
         :category :error
         :object Level/SEVERE}]
       (map-indexed #(assoc %2 :weight %1))))

(def ^:private level-to-keyword
  (into {} (map (juxt :object :name) log-levels)))

(def ^:private keyword-to-level
  (set/map-invert level-to-keyword))

(defn- event->record
  "Convert a Cider log event into a Java LogRecord."
  ^LogRecord [{:keys [arguments exception level logger message]}]
  (doto (LogRecord. (keyword-to-level level Level/INFO) message)
    (.setLoggerName (or logger ""))
    (.setParameters (into-array Object arguments))
    (.setThrown exception)))

(defn- thread-by-id
  "Find the thread by `id`."
  ^Thread [id]
  (some #(and (= id (.getId ^Thread %)) %)
        (keys (Thread/getAllStackTraces))))

(defn- record->event
  "Convert a Java LogRecord into a Cider log event."
  [^LogRecord record]
  (let [exception (.getThrown record)]
    (cond-> {:arguments (vec (.getParameters record))
             :id (java.util.UUID/randomUUID)
             :level (level-to-keyword (.getLevel record))
             :logger (.getLoggerName record)
             :mdc {}
             :message (.getMessage record)
             :thread (some-> (.getThreadID record) thread-by-id .getName)
             :timestamp (.getMillis record)}
      exception (assoc :exception exception))))

(defn- add-appender
  "Attach the Logback appender."
  [framework appender]
  (let [instance (proxy [StreamHandler] []
                   (publish [^LogRecord record]
                     (swap! appender appender/add-event (record->event record))))
        ^String logger-name (or (:logger appender) (:root-logger framework))]
    (swap! appender assoc :instance instance)
    (doto ^Logger (Logger/getLogger logger-name)
      (.addHandler instance))
    framework))

(defn- remove-appender
  "Remove `appender` from the Logback `framework`."
  [framework appender]
  (let [^String logger-name (or (:logger appender) (:root-logger framework))
        logger (Logger/getLogger logger-name)]
    (.removeHandler logger (:instance @appender))
    framework))

(defn- log [framework event]
  (let [^String logger-name (or (:logger event) (:root-logger framework))]
    (.log (Logger/getLogger logger-name) (event->record event))))

(def framework
  "The Java Util Logging framework."
  {:add-appender-fn #'add-appender
   :id "jul"
   :javadoc-url "https://docs.oracle.com/en/java/javase/19/docs/api/java.logging/java/util/logging/package-summary.html"
   :levels log-levels
   :log-fn #'log
   :name "Java Util Logging"
   :remove-appender-fn #'remove-appender
   :root-logger ""
   :website-url "https://docs.oracle.com/en/java/javase/19/core/java-logging-overview.html"})
