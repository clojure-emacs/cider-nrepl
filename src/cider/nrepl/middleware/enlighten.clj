(ns cider.nrepl.middleware.enlighten
  "Instrument user code to \"light up\" when it runs.
  The instrumented code will report the value of local variables and
  report its return value.
  Implemented as an extension of the debugger."
  {:author "Artur Malabarba"}
  (:require [cider.nrepl.middleware.debug :as d]
            [cider.nrepl.middleware.util.instrument :as ins]
            [orchard.meta :as m]
            [clojure.walk :as walk]))

(defn pr-very-short [val]
  (binding [*print-length* 3, *print-level* 2]
    (pr-str val)))

(defn send-if-local
  "If locals contains sym, send its value over the debug channel.
  The value is added to `extras` under :debug-value, and `extras` is
  sent over the debug channel with the :enlighten status."
  [sym extras locals]
  (when (contains? locals sym)
    ;; Enlightened values are inlined, so let's keep them short.
    (->> (locals sym) pr-very-short
         (assoc extras :status :enlighten
                :erase-previous :true
                :debug-value)
         d/debugger-send)))

(defn wrap-function-form
  "Wrap a form representing a function/macro/special-form call.
  Return an equivalent form, instrumented to work with enlighten.

  Currently this only instruments forms that could run several times
  in a single evaluation. This is necessary so that the client can
  clean-up overlays from previous evaluations."
  [[head & args :as form] {:keys [coor] :as extras}]
  (let [erase `(d/debugger-send (assoc (:msg ~'STATE__)
                                       :coor ~coor
                                       :status :enlighten
                                       :erase-previous :true))]
    (case head
      ;; This is still compile-time, so return a form, not a function.
      fn* `#(do ~erase (apply ~form %&))
      ;; `defn` expands to `(def name (fn ...))`.
      def (let [[name val] args]
            (if (and (seq? val) (= 'fn* (first val)))
              (list head name
                    `#(do ~erase
                          (let [out# (apply ~val %&)]
                            ;; `defn` is the only non-symbol form that we enlighten.
                            (->> (assoc (:msg ~'STATE__)
                                        :coor ~coor
                                        :status :enlighten
                                        :debug-value (pr-very-short out#))
                                 d/debugger-send)
                            out#)))
              form))
      ;; Ensure that any `recur`s remain in the tail position.
      loop* (list* head (first args) erase (rest args))
      ;; Else.
      form)))

(defmacro light-form
  "Return the result of form, and maybe enlighten it."
  [form {:keys [coor] :as extras} original-form]
  (cond
    (symbol? original-form) `(do
                               (send-if-local '~original-form
                                              (assoc (:msg ~'STATE__) :coor ~coor)
                                              ~(d/sanitize-env &env))
                               ~form)
    (seq? form) (wrap-function-form form extras)
    :else form))

(defn light-reader [form]
  (ins/tag-form-recursively form #'light-form))

;;; Middleware
(defn eval-with-enlighten
  "Like `eval`, but also enlighten code."
  [form]
  (let [form1 `(d/with-initial-debug-bindings
                 ~(ins/instrument-tagged-code (light-reader form)))]
    ;; (ins/print-form form1 true)
    (eval form1)))

(defn handle-enlighten
  [h {:keys [op enlighten] :as msg}]
  (if (and (= op "eval") enlighten)
    (h (assoc msg :eval "cider.nrepl.middleware.enlighten/eval-with-enlighten"))
    (h msg)))
