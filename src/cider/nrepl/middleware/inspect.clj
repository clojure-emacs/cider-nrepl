(ns cider.nrepl.middleware.inspect
  (:require [cider.nrepl.middleware.util.inspect :as inspect]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware.session :refer [session]]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

;; I'm not sure if I should be hard-coding the decision to inspect the
;; var for macros and functions. Yet, in my opinion, the vars have
;; more valuable info than the values do in those cases.
(defn try-eval-in-ns [ns expr]
  {:pre [(symbol? ns) (string? expr)]}
  (when-let [ns (find-ns ns)]
    (try
      (binding [*ns* ns] (eval (read-string expr)))
      (catch java.lang.Throwable e
        (clojure.stacktrace/print-stack-trace e)
        nil))))

(defn lookup
  [ns expr]
  (let [hist? (= (first expr) \*)
        sym (symbol expr)
        ns  (symbol ns)
        val (or (and hist? (ns-resolve (symbol "clojure.core") sym)) ;; *1/*2/*3
                (try-eval-in-ns ns expr)
                (find-ns sym) ;; is namespace
                (and (namespace sym) (resolve sym)) ;; is a fully qualified sym
                (ns-resolve ns sym))] ;; lookup in current ns
    (cond (or (instance? Class val) (instance? clojure.lang.Namespace val)
              (:macro (meta val)) (and (instance? clojure.lang.Var val) (fn? @val)))
          val ;; aesthetic call, if class/ns/macro or fn show var
          (instance? clojure.lang.ARef val)
          @val ;; deref if ARef (e.g. Var)
          (nil? val)
          (throw (java.lang.Throwable.))
          :default
          val ;; otherwise, show var value
          )))

(defn inspector-op [inspector {:keys [session op ns sym idx] :as msg}]
  (try
    (cond
     ;; new
     (= op "inspect-start")
     (let [val (lookup ns sym)]
       (inspect/start inspector val))
     (= op "inspect-refresh")
     (inspect/start inspector (:value inspector))
     (= op "inspect-pop")    (inspect/up inspector)
     (= op "inspect-push")  (inspect/down inspector (Integer/parseInt idx))
     (= op "inspect-reset") (inspect/clear inspector)
     :default nil)
    (catch java.lang.Throwable e
      (clojure.stacktrace/print-stack-trace e)
      (assoc inspector :rendered (list "Unable to inspect: " sym)))))

(def ^:private current-inspector (atom nil))

(defn session-inspector-value [{:keys [session] :as msg}]
  (let [inspector (or @current-inspector (inspect/fresh))
        result (inspector-op inspector msg)]
    (cond
     (nil? result) nil
     (and (:status result) (not= (:status result) :done)) result
     :default
     (do (reset! current-inspector result)
         {:value (inspect/serialize-render result)}))))

(defn wrap-inspect
  [handler]
  (fn [{:keys [transport] :as msg}]
    (if-let [result (session-inspector-value msg)]
      (transport/send transport (response-for msg {:status :done} result))
      (handler msg))))

(set-descriptor! #'wrap-inspect
  {:requires #{#'session}
   :handles {"inspect-reset" {}
             "inspect-refresh" {}
             "inspect-push" {}
             "inspect-pop" {}
             "inspect-start" {}}})
