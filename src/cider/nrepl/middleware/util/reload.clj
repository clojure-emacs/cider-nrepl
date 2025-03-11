(ns cider.nrepl.middleware.util.reload
  "Common parts for the code-reloading middleware namespaces."
  {:added "0.47.0"}
  (:require
   [clojure.main :refer [repl-caught]]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.middleware.print :as print]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.misc :as misc]
   [orchard.stacktrace :as stacktrace]))

(defn error-reply
  [{:keys [error error-ns]}
   {:keys [::print/print-fn transport] :as msg}]

  (transport/send
   transport
   (response-for msg (cond-> {:status :error}
                       error (assoc :error (stacktrace/analyze error print-fn))
                       error-ns (assoc :error-ns error-ns))))

  (binding [*msg* msg
            *err* (print/replying-PrintWriter :err msg {})]
    (repl-caught error)))

(defn- zero-arity-callable? [func]
  (and (fn? (if (var? func) @func func))
       (->> (:arglists (meta func))
            (some #(or (= [] %) (= '& (first %)))))))

(defn- resolve-and-invoke
  "Takes a string and tries to coerce a function from it. If that
  function is a function of possible zero arity (ie, truly a thunk or
  has optional parameters and can be called with zero args, it is
  called. Returns whether the function was resolved."
  [sym {:keys [_session] :as msg}]
  (let [the-var (some-> sym misc/as-sym resolve)]

    (when (and (var? the-var)
               (not (zero-arity-callable? the-var)))
      (throw (IllegalArgumentException.
              (format "%s is not a function of no arguments" sym))))

    (binding [*msg* msg
              *out* (print/replying-PrintWriter :out msg {})
              *err* (print/replying-PrintWriter :err msg {})]
      (when (var? the-var)
        (@the-var))
      (var? the-var))))

(defn before-reply [{:keys [before transport] :as msg}]
  (when before
    (transport/send
     transport
     (response-for msg {:status :invoking-before
                        :before before}))

    (let [resolved? (resolve-and-invoke before msg)]
      (transport/send
       transport
       (response-for msg
                     {:status (if resolved?
                                :invoked-before
                                :invoked-not-resolved)
                      :before before})))))

(defn after-reply [error
                   {:keys [after transport] :as msg}]
  (when (and (not error) after)
    (try
      (transport/send
       transport
       (response-for msg {:status :invoking-after
                          :after after}))

      (let [resolved? (resolve-and-invoke after msg)]
        (transport/send
         transport
         (response-for msg {:status (if resolved?
                                      :invoked-after
                                      :invoked-not-resolved)
                            :after after})))

      (catch Exception e
        (error-reply {:error e} msg)))))
