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

;; Most recent error

(defn- analyze-most-recent
  "Analyze the most recent exception."
  [{:keys [session ::print/print-fn] :as msg}]
  (send-analysis msg (analyzer/analyze (@session #'*e) print-fn)))

(defn- handle-most-recent
  "Handle the stacktrace request."
  [{:keys [session] :as msg}]
  (if (and session (@session #'*e))
    (analyze-most-recent msg)
    (no-error msg))
  (done msg))

;; Parse stacktrace

(defn- parse-stacktrace
  "Parse the stacktrace parameter."
  [{:keys [stacktrace] :as msg}]
  (if-let [analysis (some-> stacktrace parser/parse analyzer/analyze)]
    (send-analysis msg analysis)
    (no-error msg)))

(defn- handle-parse-stacktrace
  "Handle the parse stacktrace request."
  [{:keys [stacktrace] :as msg}]
  (if (not (str/blank? stacktrace))
    (parse-stacktrace msg)
    (no-error msg))
  (done msg))

(defn handle-stacktrace
  "Handle stacktrace ops."
  [_ {:keys [op] :as msg}]
  (case op
    "stacktrace" (handle-most-recent msg)
    "parse-stacktrace" (handle-parse-stacktrace msg)))
