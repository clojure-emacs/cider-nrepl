(ns cider.nrepl.middleware.stacktrace
  "Cause and stacktrace analysis for exceptions"
  {:author "Jeff Valk"}
  (:require
   [cider.nrepl.middleware.util.nrepl :refer [notify-client]]
   [clojure.string :as str]
   [haystack.analyzer :as analyzer]
   [haystack.parser :as parser]
   [nrepl.middleware.print :as print]
   [cider.nrepl.middleware.inspect :as middleware.inspect]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]))

(defn- done
  "Send the done response to the client."
  [{:keys [transport] :as msg}]
  (t/send transport (response-for msg :status :done)))

(defn- no-error
  "Send the no error response to the client."
  [{:keys [transport] :as msg}]
  (t/send transport (response-for msg :status :no-error)))

(defn- send-analysis
  "Send the stacktrace analysis response to the client."
  [{:keys [transport] :as msg} analysis]
  (doseq [cause analysis]
    (t/send transport (response-for msg cause))))

;; Analyze the last stacktrace

(def ^:dynamic *last-exception* nil)

(defn- analyze-last-stacktrace
  "Analyze the last exception."
  [{:keys [session ::print/print-fn] :as msg}]
  (let [last-exception (@session #'*e)]
    (swap! session assoc #'*last-exception* last-exception) ;; note that *e can change later, so this entry isn't redundant
    (send-analysis msg (analyzer/analyze last-exception print-fn))))

(defn- handle-analyze-last-stacktrace-op
  "Handle the analyze last stacktrace op."
  [{:keys [session] :as msg}]
  (if (and session (@session #'*e))
    (analyze-last-stacktrace msg)
    (no-error msg))
  (done msg))

;; Analyze stacktrace

(defn- analyze-stacktrace
  "Parse and analyze the `stacktrace`."
  [{:keys [stacktrace] :as msg}]
  (if-let [analysis (some-> stacktrace parser/parse analyzer/analyze)]
    (send-analysis msg analysis)
    (no-error msg)))

(defn- handle-analyze-stacktrace-op
  "Handle the analyze stacktrace op."
  [{:keys [stacktrace] :as msg}]
  (if (not (str/blank? stacktrace))
    (analyze-stacktrace msg)
    (no-error msg))
  (done msg))

;; Stacktrace

(defn- handle-stacktrace-op
  "Handle the stacktrace op."
  [msg]
  (handle-analyze-last-stacktrace-op msg)
  (notify-client msg "The `stacktrace` op is deprecated, please use `analyze-last-stacktrace` instead." :warning))

(defn handle-inspect-last-exception-op [{:keys [index transport] :as msg}]
  (let [le (when index
             (some-> msg :session deref (get #'*last-exception*)))
        cause (when le
                (loop [n 0
                       cause le]
                  (if (= n index)
                    cause
                    (when cause
                      (recur (inc n)
                             (some-> ^Throwable cause .getCause))))))]
    (if cause
      (t/send transport (middleware.inspect/inspect-reply* msg cause))
      (no-error msg))
    (done msg)))

(defn handle-stacktrace
  "Handle stacktrace ops."
  [_ {:keys [op] :as msg}]
  (case op
    "analyze-last-stacktrace" (handle-analyze-last-stacktrace-op msg)
    "inspect-last-exception"  (handle-inspect-last-exception-op msg)
    "analyze-stacktrace"      (handle-analyze-stacktrace-op msg)
    "stacktrace"              (handle-stacktrace-op msg)))
