(ns cider.nrepl.middleware.track-state
  "State tracker for client sessions."
  {:author "Artur Malabarba"}
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport])
  (:import clojure.tools.nrepl.transport.Transport))

(defn assoc-state
  "Return response with a :state entry assoc'ed."
  [response msg]
  (assoc response :state {:repl-type (if (cljs/grab-cljs-env msg) :cljs :clj)}))

(defn make-transport
  "Return a Transport that defers to `transport` and possibly notifies
  about the state."
  [{:keys [^Transport transport] :as msg}]
  (reify Transport
    (recv [this] (.recv transport))
    (recv [this timeout] (.recv transport timeout))
    (send [this {:keys [status] :as response}]
      (.send transport (cond-> response
                         (contains? status :done) (assoc-state msg))))))

(defn wrap-tracker
  "Middleware that tracks relevant server info and notifies the client."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (#{"eval" "load-file" "refresh" "refresh-all" "refresh-clear" "undef"} op)
      (handler (assoc msg :transport (make-transport msg)))
      (handler msg))))

(set-descriptor!
 #'wrap-tracker
 (cljs/requires-piggieback
  {:expects #{"eval"}
   :handles
   {"track-state-middleware"
    {:doc "Enhances the `eval` op by notifying the client of the current REPL state. Currently, only the REPL type (Clojure or ClojureScript) is informed."}}}))
