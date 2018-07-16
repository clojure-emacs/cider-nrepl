(ns cider.nrepl.middleware.pprint
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [orchard.misc :as u]
            [clojure.pprint :refer [pprint *print-right-margin*]]
            [nrepl.middleware.interruptible-eval :refer [*msg*]]
            [nrepl.middleware.pr-values :refer [pr-values]]
            [nrepl.middleware.session :as session]
            [nrepl.misc :refer [response-for]]
            [nrepl.transport :as transport])
  (:import nrepl.transport.Transport))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; fipp-printer and puget-printer are not loaded in the main `(ns)` form
;; because they are optional features that take hundreds of milliseconds
;; to load. Therefore, we delay the require/resolve process of the alternate
;; pprint features until the user tries to use it.

(def ^:private fipp-printer
  (delay
   (do
     (require 'fipp.edn)
     (resolve 'fipp.edn/pprint))))

(defn fipp-pprint [object]
  (@fipp-printer object {:width (or *print-right-margin* 72)}))

(def ^:private puget-printer
  (delay
   (do
     (require 'puget.printer)
     (resolve 'puget.printer/pprint))))

(defn puget-pprint [object]
  (@puget-printer object {:width (or *print-right-margin* 72)
                          :seq-limit *print-length*}))

(defn- resolve-pprint-fn
  [sym]
  (if-let [pp-fn (-> sym u/as-sym find-var)]
    pp-fn
    (throw (IllegalArgumentException. (format "%s is not resolvable to a var" sym)))))

(defn handle-pprint-fn
  [handler msg]
  (let [{:keys [pprint-fn print-length print-level print-meta print-right-margin session]
         :or {pprint-fn 'clojure.pprint/pprint}}
        msg]
    (handler (assoc msg :pprint-fn (fn [object]
                                     (binding [*print-length* (or print-length (get @session #'*print-length*))
                                               *print-level* (or print-level (get @session #'*print-level*))
                                               *print-meta* (or print-meta (get @session #'*print-meta*))
                                               ;; pprint/*print-right-margin* is not bound by session middleware
                                               *print-right-margin* (or print-right-margin *print-right-margin*)]
                                       ((resolve-pprint-fn pprint-fn) object)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- pprint-writer
  [{:keys [session transport] :as msg}]
  (#'session/session-out :pprint-out (:id (meta session)) transport))

(defn pprint-reply
  [{:keys [pprint-fn session transport] :as msg} response]
  (with-open [writer (pprint-writer msg)]
    ;; Binding `*msg*` sets the `:id` slot when printing to an nREPL session
    ;; PrintWriter (as created by `pprint-writer`), which the client requires to
    ;; handle the response correctly.
    (binding [*msg* msg
              *out* writer]
      (let [value (cljs/response-value msg response)
            print-fn (if (string? value) println pprint-fn)]
        (print-fn value))))
  (transport/send transport (response-for msg :pprint-sentinel {})))

(defn pprint-transport
  [{:keys [right-margin ^Transport transport] :as msg}]
  (reify Transport
    (recv [this] (.recv transport))
    (recv [this timeout] (.recv transport timeout))
    (send [this response]
      (when (contains? response :value)
        (pprint-reply msg response))
      (.send transport (dissoc response :value)))))

(defn handle-pprint
  [handler msg]
  (let [{:keys [op pprint]} msg]
    (handler (if (and pprint (#{"eval" "load-file"} op))
               (assoc msg :transport (pprint-transport msg))
               msg))))
