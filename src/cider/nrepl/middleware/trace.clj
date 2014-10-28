(ns cider.nrepl.middleware.trace
  (:require [clojure.string :as s]
            [clojure.tools.trace :as trace]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn toggle-trace
  [{:keys [ns sym transport] :as msg}]
  (try
    (if-let [v (ns-resolve (symbol ns) (symbol sym))]
      (if (trace/traced? v)
        (do (trace/untrace-var* v)
            (t/send transport (response-for msg
                                            :status :done
                                            :value (str sym " untraced."))))
        (do (trace/trace-var* v)
            (t/send transport (response-for msg
                                            :status :done
                                            :value (str sym " traced.")))))
      (t/send transport (response-for msg
                                      :status #{:error :done}
                                      :value "no such var")))
    (catch Exception e
      (t/send transport (response-for msg
                                      :status #{:error :done}
                                      :value (.getMessage e))))))

(defn wrap-trace
  "Middleware that toggles tracing of a given var."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "toggle-trace" op)
      (toggle-trace msg)
      (handler msg))))

(set-descriptor!
 #'wrap-trace
 {:handles
  {"toggle-trace"
   {:doc "Toggle tracing of a given var."
    :requires {"sym" "The symbol to trace"
               "ns" "The current namespace"}
    :returns {"status" "done"}}}})
