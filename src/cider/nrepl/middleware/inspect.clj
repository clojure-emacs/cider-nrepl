(ns cider.nrepl.middleware.inspect
  (:require [cider.nrepl.middleware.util.inspect :as inspect]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.middleware.pr-values :refer [pr-values]]
            [clojure.tools.nrepl.transport :as transport])
  (:import clojure.tools.nrepl.transport.Transport))

(def ^:dynamic *inspector* (inspect/fresh))

(defn swap-inspector!
  [{:keys [session] :as msg} f & args]
  (-> session
      (swap! update-in [#'*inspector*] #(apply f % args))
      (get #'*inspector*)))

(defn inspect-reply
  [{:keys [transport] :as msg} eval-response]
  (let [inspector (swap-inspector! msg inspect/start (:value eval-response))]
    (transport/send
     transport
     (response-for msg :value (:rendered inspector)))))

(defn inspector-transport
  [{:keys [^Transport transport] :as msg}]
  (reify Transport
    (recv [this] (.recv transport))
    (recv [this timeout] (.recv transport timeout))
    (send [this response]
      (if (contains? response :value)
        (inspect-reply msg response)
        (.send transport response))
      this)))

(defn eval-msg
  [{:keys [inspect] :as msg}]
  (if inspect
    (assoc msg :transport (inspector-transport msg))
    msg))

(defn eval-reply
  [handler msg]
  (handler (eval-msg msg)))

(defn pop-reply
  [{:keys [transport] :as msg}]
  (let [inspector (swap-inspector! msg inspect/up)]
    (transport/send
     transport
     (response-for msg :value (:rendered inspector) :status :done))))

(defn push-reply
  [{:keys [idx transport] :as msg}]
  (let [idx (Integer/parseInt idx)
        inspector (swap-inspector! msg inspect/down idx)]
    (transport/send
     transport
     (response-for msg :value (:rendered inspector) :status :done))))

(defn refresh-reply
  [{:keys [transport] :as msg}]
  (let [inspector (swap-inspector! msg #(or % (inspect/fresh)))]
    (transport/send
     transport
     (response-for msg :value (:rendered inspector) :status :done))))

(defn wrap-inspect
  "Middleware that adds a value inspector option to the eval op. Passing a
  non-nil value in the `:inspect` slot will cause the last value returned by
  eval to be inspected. Returns a string representation of the resulting
  inspector's state in the `:value` slot."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "eval" (eval-reply handler msg)
      "inspect-pop" (pop-reply msg)
      "inspect-push" (push-reply msg)
      "inspect-refresh" (refresh-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-inspect
 {:requires #{"clone" #'pr-values}
  :expects #{"eval"}
  :handles {"inspect-pop" {}
            "inspect-push" {}
            "inspect-refresh" {}}})
