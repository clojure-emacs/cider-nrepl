(ns cider.nrepl.middleware.trace
  (:require [clojure.string :as s]
            [clojure.tools.trace :as trace]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [cider.nrepl.middleware.util.misc :as u]))

(defn toggle-trace-var
  [{:keys [ns sym transport] :as msg}]
  (try
    (if-let [v (ns-resolve (symbol ns) (symbol sym))]
      (if (trace/traceable? v)
        (if (trace/traced? v)
          (do (trace/untrace-var* v)
              (t/send transport (response-for msg
                                              :status :done
                                              :var-name (str v)
                                              :var-status "untraced")))
          (do (trace/trace-var* v)
              (t/send transport (response-for msg
                                              :status :done
                                              :var-name (str v)
                                              :var-status "traced"))))
        (t/send transport (response-for msg
                                        :status :done
                                        :var-name (str v)
                                        :var-status "not-traceable")))
      (t/send transport (response-for msg
                                      :status #{:toggle-trace-error :done}
                                      :var-status "not-found")))
    (catch Exception e
      (t/send transport (response-for msg (u/err-info e :toggle-trace-error))))))

(def traced-ns (atom #{}))

(defn toggle-trace-ns
  [{:keys [ns transport] :as msg}]
  (try
    (if-let [ns (find-ns (symbol ns))]
      (if (contains? @traced-ns ns)
        (do (trace/untrace-ns ns)
            (swap! traced-ns disj ns)
            (t/send transport (response-for msg
                                            :status :done
                                            :ns-status "untraced")))
        (do (trace/trace-ns ns)
            (swap! traced-ns conj ns)
            (t/send transport (response-for msg
                                            :status :done
                                            :ns-status "traced"))))
      (t/send transport (response-for msg
                                      :status #{:toggle-trace-error :done}
                                      :ns-status "not-found")))
    (catch Exception e
      (t/send transport (response-for msg (u/err-info e :toggle-trace-error))))))

(defn wrap-trace
  "Middleware that toggles tracing of a given var."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "toggle-trace-var" (toggle-trace-var msg)
      "toggle-trace-ns"  (toggle-trace-ns msg)
      (handler msg))))

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
