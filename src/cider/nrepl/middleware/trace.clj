(ns cider.nrepl.middleware.trace
  (:require
   [cider.nrepl.middleware.util :refer [respond-to]]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.trace :as trace])
  (:import
   [java.util UUID]))

(defn toggle-trace-var
  [{:keys [ns sym]}]
  (if-let [v (ns-resolve (symbol ns) (symbol sym))]
    (if (trace/traceable? v)
      (if (trace/traced? v)
        (do (trace/untrace-var* v)
            {:var-name (str v) :var-status "untraced"})
        (do (trace/trace-var* v)
            {:var-name (str v) :var-status "traced"}))
      {:var-name (str v) :var-status "not-traceable"})
    {:status #{:toggle-trace-error :done} :var-status "not-found"}))

(defn toggle-trace-ns
  [{:keys [ns]}]
  (if-let [ns (find-ns (symbol ns))]
    (if (contains? @@#'trace/traced-nses ns)
      (do (trace/untrace-ns* ns)
          {:ns-status "untraced"})
      (do (trace/trace-ns* ns)
          {:ns-status "traced"}))
    {:ns-status "not-found"}))

(defn list-traced
  "Return the currently traced vars and namespaces, as strings."
  [_msg]
  {:traced-vars (mapv str @@#'trace/traced-vars)
   :traced-nses (mapv str @@#'trace/traced-nses)})

(defn untrace-all
  "Untrace every currently traced var and namespace.
  Returns the number of vars that were untraced."
  [_msg]
  (let [untraced-count (count @@#'trace/traced-vars)]
    (trace/untrace-all)
    {:untraced-count untraced-count}))

(def ^:private subscriptions
  "Map of subscription id -> the orchard trace listener registered for it."
  (atom {}))

(defn- serialize-event
  "Make a trace EVENT bencode-friendly for transport."
  [event]
  (update event :phase name))

(defn trace-subscribe
  "Stream trace events to the client until `cider/trace-unsubscribe`.
  Registers an orchard trace listener that forwards each event on this
  request, and routes trace output to listeners instead of the REPL."
  [msg]
  (let [id (str (UUID/randomUUID))
        listener (fn [event]
                   (respond-to msg
                               :cider/trace-event (serialize-event event)
                               :status :cider/trace-event))]
    (swap! subscriptions assoc id listener)
    (trace/add-trace-listener listener)
    (trace/set-output-mode! :listeners)
    {:cider/trace-subscribe id}))

(defn trace-unsubscribe
  "Stop streaming trace events for the given subscription.
  Restores REPL trace output once the last subscription is gone."
  [{:keys [subscription]}]
  (when-let [listener (get @subscriptions subscription)]
    (trace/remove-trace-listener listener)
    (swap! subscriptions dissoc subscription))
  (when (empty? @subscriptions)
    (trace/set-output-mode! :repl))
  {:cider/trace-unsubscribe subscription})

(defn handle-trace [handler msg]
  (with-safe-transport handler msg
    "cider/toggle-trace-var" [toggle-trace-var :toggle-trace-error]
    "toggle-trace-var" [toggle-trace-var :toggle-trace-error]
    "cider/toggle-trace-ns" [toggle-trace-ns :toggle-trace-error]
    "toggle-trace-ns" [toggle-trace-ns :toggle-trace-error]
    "cider/list-traced" [list-traced :list-traced-error]
    "cider/untrace-all" [untrace-all :untrace-all-error]
    "cider/trace-subscribe" [trace-subscribe :trace-subscribe-error]
    "cider/trace-unsubscribe" [trace-unsubscribe :trace-unsubscribe-error]))
