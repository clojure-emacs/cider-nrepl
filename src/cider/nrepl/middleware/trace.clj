(ns cider.nrepl.middleware.trace
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.trace :as trace]))

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

(def traced-ns (atom #{}))

(defn toggle-trace-ns
  [{:keys [ns]}]
  (if-let [ns (find-ns (symbol ns))]
    (if (contains? @@#'trace/traced-nses ns)
      (do (trace/untrace-ns* ns)
          {:ns-status "untraced"})
      (do (trace/trace-ns* ns)
          {:ns-status "traced"}))
    {:ns-status "not-found"}))

(defn handle-trace [handler msg]
  (with-safe-transport handler msg
    "cider/toggle-trace-var" [toggle-trace-var :toggle-trace-error]
    "toggle-trace-var" [toggle-trace-var :toggle-trace-error]
    "cider/toggle-trace-ns" [toggle-trace-ns :toggle-trace-error]
    "toggle-trace-ns" [toggle-trace-ns :toggle-trace-error]))
