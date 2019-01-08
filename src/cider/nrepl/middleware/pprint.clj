(ns cider.nrepl.middleware.pprint
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   cider.nrepl.pprint
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.middleware.pr-values :refer [pr-values]]
   [nrepl.middleware.session :as session]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.misc :as u])
  (:import
   nrepl.transport.Transport))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEPRECATED in favour of the built-in pprint support added in nREPL 0.5 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Remove this middleware (or make it a no-op).

(defn- resolve-pprint-fn
  [sym]
  (if-let [pp-fn (-> sym u/as-sym find-var)]
    pp-fn
    (throw (IllegalArgumentException. (format "%s is not resolvable to a var" sym)))))

(defn handle-pprint-fn
  [handler msg]
  (let [{:keys [pprint-fn]
         :or {pprint-fn 'cider.nrepl.pprint/pprint}}
        msg]
    (handler (assoc msg :pprint-fn (resolve-pprint-fn pprint-fn)))))
