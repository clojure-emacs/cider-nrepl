(ns cider.log.framework
  (:require [cider.log.appender :as appender]
            [cider.log.event :as event]))

(def ^:dynamic *frameworks*
  ['cider.log.framework.logback/framework
   'cider.log.framework.jul/framework
   ;; 'cider.log.framework.log4j2/framework
   ])

(defn- ex-appender-not-found
  "Return the appender not found exception info."
  [framework appender]
  (ex-info (format "Log appender %s not found in framework %s"
                   (:id appender) (:id framework))
           {:error :log-appender-not-found
            :framework (:id framework)
            :appender (:id appender)}))

(defn find-appender!
  "Find the `appender` by its :id key in `framework`, or throw an exception."
  [framework appender]
  (or (get-in framework [:appenders (:id appender)])
      (throw (ex-appender-not-found framework appender))))

(defn appenders
  "Return the appenders of the log `framework`."
  [framework]
  (vals (:appenders framework)))

(defn appender [framework appender]
  (some #(and (= (:id appender) (:id (deref %))) %)
        (appenders framework)))

(defn add-appender
  "Add the log `appender` to the `framework`."
  [framework appender]
  (let [atom-appender (atom (-> {:levels (:levels framework)
                                 :logger (:root-logger framework)}
                                (merge appender)
                                (appender/make-appender)))]
    (-> (assoc-in framework [:appenders (:id @atom-appender)] atom-appender)
        ((:add-appender-fn framework) atom-appender))))

(defn add-consumer
  "Add `consumer` to the `appender` of the log `framework`."
  [framework appender consumer]
  (let [atom-appender (find-appender! framework appender)]
    (swap! atom-appender appender/add-consumer consumer)
    framework))

(defn clear-appender
  "Clear the log `appender` of the `framework`."
  [framework appender]
  (let [atom-appender (find-appender! framework appender)]
    (swap! atom-appender appender/clear)
    framework))

(defn consumer
  "Find the `consumer` listening to the `appender` of the log `framework`."
  [framework appender consumer]
  (let [atom-appender (find-appender! framework appender)]
    (appender/consumer-by-id @atom-appender (:id consumer))))

(defn event
  "Lookup the event by `id` in the `appender` of the `framework`."
  [framework appender id]
  (let [atom-appender (find-appender! framework appender)]
    (appender/event @atom-appender id)))

(defn events
  "Return the log events captured by the `appender` of the `framework`."
  [framework appender]
  (let [atom-appender (find-appender! framework appender)]
    (appender/events @atom-appender)))

(defn log
  "Log the `event` with the `framework`."
  [framework event]
  ((:log-fn framework) framework event)
  nil)

(defn remove-appender
  "Remove the log `appender` from the `framework`."
  [framework appender]
  (let [atom-appender (find-appender! framework appender)]
    (-> ((:remove-appender-fn framework) framework atom-appender)
        (update :appenders dissoc (:id @atom-appender)))))

(defn remove-consumer
  "Remove the `consumer` listening to the `appender` of the log `framework`."
  [framework appender consumer]
  (let [atom-appender (find-appender! framework appender)]
    (swap! atom-appender appender/remove-consumer consumer)
    framework))

(defn update-appender
  "Update the `appender` of the log `framework`."
  [framework appender]
  (let [atom-appender (find-appender! framework appender)]
    (swap! atom-appender appender/update-appender appender)
    framework))

(defn update-consumer
  "Update the `consumer` listening to the `appender` of the log `framework`."
  [framework appender consumer]
  (let [atom-appender (find-appender! framework appender)]
    (swap! atom-appender appender/update-consumer consumer)
    framework))

(defn resolve-framework
  "Resolve the framework bound to `framework-sym`."
  [framework-sym]
  (try (require (-> framework-sym namespace symbol))
       (some-> framework-sym resolve deref)
       (catch Exception _)))

(defn resolve-frameworks
  "Resolve the framework bound to `framework-syms`."
  ([]
   (resolve-frameworks *frameworks*))
  ([framework-syms]
   (reduce (fn [frameworks framework-sym]
             (if-let [framework (resolve-framework framework-sym)]
               (assoc frameworks (:id framework) framework)
               frameworks))
           {} framework-syms)))

(defn search-events
  "Search the log events captured by the `appender` of the log
  `framework` and filter them by the search `criteria`."
  [framework appender criteria]
  (->> (events framework appender)
       (event/search (:levels framework) criteria)))

(defn shutdown
  "Shutdown all consumers and appenders of the log `framework`."
  [framework]
  (reduce (fn [framework appender]
            (-> (reduce (fn [framework consumer]
                          (remove-consumer framework @appender consumer))
                        framework (appender/consumers @appender))
                (remove-appender @appender)))
          framework (appenders framework)))
