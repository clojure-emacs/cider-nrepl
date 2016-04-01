(ns cider.nrepl.middleware.trace
  (:require [clojure.string :as s]
            [clojure.tools.trace :as trace]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport] :as err]))

(defn toggle-trace-var
  [{:keys [ns sym transport] :as msg}]
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
  [{:keys [ns transport] :as msg}]
  (if-let [ns (find-ns (symbol ns))]
    (if (contains? @traced-ns ns)
      (do (trace/untrace-ns ns)
          (swap! traced-ns disj ns)
          {:ns-status "untraced"})
      (do (trace/trace-ns ns)
          (swap! traced-ns conj ns)
          {:ns-status "traced"}))
    {:ns-status "not-found"}))

(defn wrap-trace
  "Middleware that toggles tracing of a given var."
  [handler]
  (with-safe-transport handler
    "toggle-trace-var" [toggle-trace-var :toggle-trace-error]
    "toggle-trace-ns"  [toggle-trace-ns :toggle-trace-error]))

(set-descriptor!
 #'wrap-trace
 {:handles
  {"toggle-trace-var"
   {:doc "Toggle tracing of a given var."
    :requires {"sym" "The symbol to trace"
               "ns" "The current namespace"}
    :returns {"var-status" "The result of tracing operation"
              "var-name" "The fully-qualified name of the traced/untraced var"}}
   "toggle-trace-ns"
   {:doc "Toggle tracing of a given ns."
    :requires {"ns" "The namespace to trace"}
    :returns {"ns-status" "The result of tracing operation"}}}})
