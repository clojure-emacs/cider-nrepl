(ns cider.nrepl.middleware.debug
  (:require [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [cider.nrepl.middleware.util.instrument :refer [instrument]]
            [debugger.time :as t]
            [debugger.config :as c]
            [debugger.core :refer [break]]))

;;;; ## Interaction with the client
;;; `wrap-debug` receives an initial message from the client, stores
;;; it in `debugger-message`, and `breakpoint` answers it.
(def debugger-message (atom nil))

(defn wrap-debug [h]
  (fn [{:keys [op force] :as msg}]
    (if (= "init-debugger" op)
      (let [stored-message @debugger-message]
        (if (and stored-message (not force))
          (transport/send (:transport msg)
                          (response-for msg :status :done))
          (do (when stored-message
                (transport/send (:transport stored-message)
                                (response-for stored-message :status :done)))
              ;; TODO: Redefine this in the session binding map, see inspect.clj.
              (reset! debugger-message msg))))
      (h msg))))

(defn breakpoint
  "Send value and coordinates to the client through the debug channel.
  Sends a response to the message stored in debugger-message."
  [value extras]
  ;; If the user has recently replied (quit) to a `break` statement,
  ;; then debugger will not ask for input. Therefore, have to avoid
  ;; sending the following message in these cases.
  (if (->> (t/now) (t/interval @c/*last-quit-at*) t/in-seconds (< c/*skip-repl-if-last-quit-ago*))
    ;; TODO: If clj-debugger makes the `read` call in `read-fn`
    ;; configurable (perhaps with a dynamic variable) we would have to
    ;; divide our breakpoint into two messages.
    (do (transport/send
         (:transport @debugger-message)
         (response-for @debugger-message
                       (assoc extras
                              :debug-value (pr-str value)
                              :breakfunction nil)))
        ;; Send the actual break.
        (binding [*out* (new java.io.StringWriter)]
          (break value)))
    ;; Notify cider that a (break) is incoming, along with the value
    ;; of the instrumented sexp and instructions on how to find it in
    ;; the code.
    value))

(defn instrument-and-eval
  "Instrument form and evaluate the result.
  Call cider.nrepl.middleware.util.instrument."
  [ex form]
  (eval
   (instrument (merge {:coor [], :breakfunction breakpoint} ex)
               form)))

(set-descriptor!
 #'wrap-debug
 {:handles
  {"init-debugger"
   {:doc "Initialize the debugger so that #'breakpoint works correctly.
This does not respond immediately. It sends a response when a breakpoint is reached."
    :requires {"id" "A message id that will be responded to when a breakpoint is reached."}
    :returns {"status" "debugging"}}}})
