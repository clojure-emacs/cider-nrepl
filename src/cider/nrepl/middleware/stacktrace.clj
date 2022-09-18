(ns cider.nrepl.middleware.stacktrace
  "Cause and stacktrace analysis for exceptions"
  {:author "Jeff Valk"}
  (:require
   [nrepl.middleware.print :as print]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]
   [orchard.stacktrace.analyzer :as stacktrace.analyzer]))

(defn handle-stacktrace
  [_ {:keys [session transport ::print/print-fn] :as msg}]
  (if-let [e (@session #'*e)]
    (doseq [cause (stacktrace.analyzer/analyze e print-fn)]
      (t/send transport (response-for msg cause)))
    (t/send transport (response-for msg :status :no-error)))
  (t/send transport (response-for msg :status :done)))
