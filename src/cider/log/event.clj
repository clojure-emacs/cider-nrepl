(ns cider.log.event
  "Log event related utilities like searching and calculating frequencies."
  {:author "r0man"}
  (:require [clojure.string :as str])
  (:import [java.util.regex Pattern]))

(defn- exception-name
  "Return the `exception` class name."
  [^Throwable exception]
  (some-> exception .getClass .getName))

(defn exception-frequencies
  "Return the exception name frequencies of `events`."
  [events]
  (frequencies (keep #(some-> % :exception exception-name) events)))

(defn logger-frequencies
  "Return the logger name frequencies of `events`."
  [events]
  (frequencies (map :logger events)))

(defn level-frequencies
  "Return the log level frequencies of `events`."
  [events]
  (frequencies (map :level events)))

(defn search-filter
  "Return a predicate function that computes if a given event matches the search criteria."
  [levels {:keys [end-time exceptions level loggers pattern start-time threads]}]
  (let [exceptions (set exceptions)
        level->weight (into {} (map (juxt :name :weight) levels))
        level-weight (when (or (string? level) (keyword? level))
                       (some-> level name str/upper-case keyword level->weight))
        loggers (set loggers)
        threads (set threads)
        pattern (cond
                  (string? pattern)
                  (try (re-pattern pattern) (catch Exception _))
                  (instance? Pattern pattern)
                  pattern)]
    (if (or (seq exceptions) (seq loggers) (seq threads) level-weight pattern start-time end-time)
      (fn [event]
        (and (or (empty? exceptions)
                 (contains? exceptions (some-> event :exception exception-name)))
             (or (nil? level-weight)
                 (>= (level->weight (:level event)) level-weight))
             (or (empty? loggers)
                 (contains? loggers (:logger event)))
             (or (empty? threads)
                 (contains? threads (:thread event)))
             (or (not pattern)
                 (some->> event :message (re-matches pattern)))
             (or (not (nat-int? start-time))
                 (>= (:timestamp event) start-time))
             (or (not (nat-int? end-time))
                 (< (:timestamp event) end-time))))
      (constantly true))))

(defn search
  "Search the log events by `criteria`."
  [levels {:keys [filters limit offset] :as _criteria} events]
  (cond->> events
    (map? filters)
    (filter (search-filter levels filters))
    (nat-int? offset)
    (drop offset)
    true
    (take (if (nat-int? limit) limit 500))))

(defn thread-frequencies
  "Return the thread frequencies of `events`."
  [events]
  (frequencies (map (comp name :thread) events)))
