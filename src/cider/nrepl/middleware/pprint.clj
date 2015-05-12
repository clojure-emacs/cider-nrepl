(ns cider.nrepl.middleware.pprint
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [clojure.pprint :refer [pprint *print-right-margin*]]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.middleware.pr-values :refer [pr-values]]
            [clojure.tools.nrepl.middleware.session :as session]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport])
  (:import clojure.tools.nrepl.transport.Transport))

(defn- pprint-writer
  [{:keys [session transport] :as msg}]
  (#'session/session-out :pprint-out (:id (meta session)) transport))

(defn pprint-reply
  [{:keys [right-margin session transport] :as msg} response]
  (with-open [writer (pprint-writer msg)]
    ;; Binding `*msg*` sets the `:id` slot when printing to an nREPL session
    ;; PrintWriter (as created by `pprint-writer`), which the client requires to
    ;; handle the response correctly.
    (binding [*msg* msg
              *out* writer
              *print-length* (get @session #'*print-length*)
              *print-level* (get @session #'*print-level*)
              *print-right-margin* right-margin]
      (let [value (cljs/response-value msg response)
            print-fn (if (string? value) println pprint)]
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

(defn wrap-pprint
  "Middleware that adds a pretty printing option to the eval op.
  Passing a non-nil value in the `:pprint` slot will cause eval to call
  clojure.pprint/pprint on its result. The `:right-margin` slot can be used to
  bind `*clojure.pprint/*print-right-margin*` during the evaluation."
  [handler]
  (fn [{:keys [op pprint right-margin] :as msg}]
    (if (and pprint (= op "eval"))
      (handler (merge msg {:transport (pprint-transport msg)}))
      (handler msg))))

(set-descriptor!
 #'wrap-pprint
 (cljs/expects-piggieback
  {:requires #{"clone" #'pr-values}
   :expects #{"eval"}
   :handles
   {"pprint-middleware"
    {:doc "Enhances the `eval` op by pretty printing the evaluation result if a `:pprint` slot is found in the msg map. Not an op by itself."
     :requires #{"clone" #'pr-values}
     :expects #{"eval"}}}}))
