(ns cider.nrepl.middleware.stacktrace
  "Cause and stacktrace analysis for exceptions"
  {:author "Jeff Valk"}
  (:require
   [clojure.string :as str]
   [nrepl.middleware.print :as print]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]
   [orchard.stacktrace.analyzer :as analyzer]
   [orchard.stacktrace.parser :as parser]))

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

(defn- analyze-most-recent?
  "Return true when the most recent exception should be analyzed,
  otherwise false."
  [{:keys [session]}]
  (and session (@session #'*e)))

(defn- analyze-most-recent
  "Analyze the most recent exception."
  [{:keys [session ::print/print-fn] :as msg}]
  (send-analysis msg (analyzer/analyze (@session #'*e) print-fn)))

(defn- analyze-parameter?
  "Return true when the stacktrace parameter should be analyzed,
  otherwise false."
  [{:keys [stacktrace]}]
  (not (str/blank? stacktrace)))

(defn- analyze-parameter
  "Analyze the stacktrace parameter."
  [{:keys [stacktrace] :as msg}]
  (if-let [analysis (some-> stacktrace parser/parse analyzer/analyze)]
    (send-analysis msg analysis)
    (no-error msg)))

(defn handle-stacktrace
  "Handle the stacktrace request."
  [_ msg]
  (cond
    (analyze-parameter? msg)
    (analyze-parameter msg)
    (analyze-most-recent? msg)
    (analyze-most-recent msg)
    :else (no-error msg))
  (done msg))
