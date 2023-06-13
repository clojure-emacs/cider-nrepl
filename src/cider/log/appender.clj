(ns cider.log.appender
  (:require [cider.log.event :as event]))

(def ^:private default-size
  "The default number of events captured by an appender."
  100000)

(def ^:private default-threshold
  "The default threshold in percentage after which log events are cleaned up.

  Events of a log appender are cleanup up if the number of events reach the
  `default-size` plus the `default-threshold` percentage of
  `default-threshold`."
  10)

(defn- garbage-collect?
  "Whether to garbage collect events, or not."
  [{:keys [event-index size threshold]}]
  (> (count event-index) (+ size (* size (/ threshold 100.0)))))

(defn- garbage-collect-events
  "Garbage collect some events of the `appender`."
  [{:keys [events event-index size] :as appender}]
  (if (garbage-collect? appender)
    (assoc appender
           :events (take size events)
           :event-index (apply dissoc event-index (map :id (drop size events))))
    appender))

(defn- add-event?
  "Whether the `event` should be added to the appender."
  [{:keys [filter-fn]} event]
  (or (nil? filter-fn) (filter-fn event)))

(defn- notify-consumers
  [{:keys [consumers] :as appender} event]
  (doseq [{:keys [callback filter-fn] :as consumer} (vals consumers)
          :when (filter-fn event)]
    (callback consumer event))
  appender)

(defn- enqueue-event
  "Enqueue the `event` to the event list of `appender`."
  [appender event]
  (update appender :events #(cons event %)))

(defn- index-event
  "Add the `event` to the index of `appender`."
  [appender event]
  (assoc-in appender [:event-index (:id event)] event))

(defn add-consumer
  "Add the `consumer` to the `appender`."
  [appender {:keys [id filters] :as consumer}]
  (assert (not (get-in appender [:consumers id]))
          (format "Consumer %s already registered" id))
  (assoc-in appender [:consumers id]
            (-> (select-keys consumer [:callback :filters :id])
                (assoc :filter-fn (event/search-filter (:levels appender) filters)))))

(defn add-event
  "Add the `event` to the `appender`."
  [appender event]
  (if (add-event? appender event)
    (-> (enqueue-event appender event)
        (index-event event)
        (notify-consumers event)
        (garbage-collect-events))
    appender))

(defn clear
  "Clear the events from the `appender`."
  [appender]
  (assoc appender :events [] :event-index {}))

(defn consumers
  "Return the consumers of the `appender`."
  [appender]
  (vals (:consumers appender)))

(defn consumer-by-id
  "Find the consumer of `appender` by `id`."
  [appender id]
  (some #(and (= id (:id %)) %) (consumers appender)))

(defn event
  "Lookup the event by `id` from the log `appender`."
  [appender id]
  (get (:event-index appender) id))

(defn events
  "Return the events from the `appender`."
  [appender]
  (take (:size appender) (:events appender)))

(defn make-appender
  "Make a hash map appender."
  [{:keys [id filters levels logger size threshold]}]
  (cond-> {:consumers {}
           :event-index {}
           :events nil
           :filters (or filters {})
           :id id
           :levels levels
           :size (or size default-size)
           :threshold (or threshold default-threshold)}
    (map? filters)
    (assoc :filter-fn (event/search-filter levels filters))
    logger
    (assoc :logger logger)))

(defn remove-consumer
  "Remove the `consumer` from the `appender`."
  [appender consumer]
  (update appender :consumers dissoc (:id consumer)))

(defn update-appender
  "Update the log `appender`."
  [appender {:keys [filters size threshold]}]
  (cond-> appender
    (map? filters)
    (assoc :filters filters :filter-fn (event/search-filter (:levels appender) filters))
    (pos-int? size)
    (assoc :size size)
    (nat-int? threshold)
    (assoc :threshold threshold)))

(defn update-consumer
  "Update the `consumer` of the `appender`."
  [appender {:keys [id filters] :as consumer}]
  (update-in appender [:consumers id]
             (fn [existing-consumer]
               (assert (:id existing-consumer)
                       (format "Consumer %s not registered" id))
               (-> existing-consumer
                   (merge (select-keys consumer [:filters]))
                   (assoc :filter-fn (event/search-filter (:levels appender) filters))))))
