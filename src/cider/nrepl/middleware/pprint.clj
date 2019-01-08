(ns cider.nrepl.middleware.pprint
  (:require
   cider.nrepl.pprint
   nrepl.misc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEPRECATED in favour of the built-in pprint support added in nREPL 0.5 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Remove this middleware (or make it a no-op).

(defn- resolve-pprint-fn
  "Resolve a namespaced symbol to a printer var. Returns the var or nil if
  the argument is nil or not resolvable."
  [var-sym]
  (when-let [var-sym (and var-sym (symbol var-sym))]
    (try
      (require (symbol (namespace var-sym)))
      (resolve var-sym)
      (catch Exception ex
        (nrepl.misc/log ex "Couldn't resolve printer function" var-sym)
        cider.nrepl.pprint/pprint))))

(defn handle-pprint-fn
  [handler msg]
  (let [{:keys [pprint-fn]
         :or {pprint-fn 'cider.nrepl.pprint/pprint}}
        msg]
    (handler (assoc msg :pprint-fn (resolve-pprint-fn pprint-fn)))))
