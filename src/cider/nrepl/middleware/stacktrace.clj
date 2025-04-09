(ns cider.nrepl.middleware.stacktrace
  "Cause and stacktrace analysis for exceptions"
  {:author "Jeff Valk"}
  (:require
   [cider.nrepl.middleware.inspect :as middleware.inspect]
   [cider.nrepl.middleware.util :refer [respond-to]]
   [cider.nrepl.middleware.util.nrepl :refer [notify-client]]
   [nrepl.middleware.print :as print]
   [nrepl.transport :as t]
   [orchard.stacktrace :as stacktrace]))

(defn- send-analysis
  "Send the stacktrace analysis response to the client."
  [msg analysis]
  (doseq [cause analysis]
    (respond-to msg cause)))

;; Analyze the last stacktrace

(defn- analyze-last-stacktrace
  "Analyze the last exception."
  [{:keys [session] :as msg}]
  (let [last-exception (@session #'*e)]
    ;; We need to remember the analyzed exception separately because we need to
    ;; provide a way to inspect it while *e can change.
    (alter-meta! session assoc ::analyzed-exception last-exception)
    (send-analysis msg (stacktrace/analyze last-exception))))

(defn- handle-analyze-last-stacktrace-op
  "Handle the analyze last stacktrace op."
  [{:keys [session] :as msg}]
  (if (@session #'*e)
    (analyze-last-stacktrace msg)
    (respond-to msg :status :no-error))
  (respond-to msg :status :done))

;; Stacktrace

(defn- handle-stacktrace-op
  "Handle the stacktrace op."
  [msg]
  (handle-analyze-last-stacktrace-op msg)
  (notify-client msg "The `stacktrace` op is deprecated, please use `analyze-last-stacktrace` instead." :warning))

(defn- get-last-exception-cause [{:keys [session index] :as msg}]
  (when index
    (let [last-exception (::analyzed-exception (meta session))
          causes (when last-exception
                   (->> (iterate #(.getCause ^Throwable %) last-exception)
                        (take-while some?)))]
      (nth causes index nil))))

(defn handle-inspect-last-exception-op [{:keys [session index transport] :as msg}]
  (let [inspect-ex-data? (= (:ex-data msg) "true")
        cause (get-last-exception-cause msg)
        object (if inspect-ex-data?
                 (ex-data cause)
                 cause)]
    (if object
      (t/send transport (middleware.inspect/inspect-reply* msg object))
      (respond-to msg :status :no-error))
    (respond-to msg :status :done)))

(defn handle-stacktrace
  "Handle stacktrace ops."
  [_ {:keys [op] :as msg}]
  (case op
    "analyze-last-stacktrace" (handle-analyze-last-stacktrace-op msg)
    "inspect-last-exception"  (handle-inspect-last-exception-op msg)
    "stacktrace"              (handle-stacktrace-op msg)))
