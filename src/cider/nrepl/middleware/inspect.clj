(ns cider.nrepl.middleware.inspect
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.inspect :as inspect]
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
  (let [value (cljs/response-value msg eval-response)
        inspector (swap-inspector! msg inspect/start value)]
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

(defn next-page-reply
  [{:keys [transport] :as msg}]
  (let [inspector (swap-inspector! msg inspect/next-page)]
    (transport/send
     transport
     (response-for msg :value (:rendered inspector) :status :done))))

(defn prev-page-reply
  [{:keys [transport] :as msg}]
  (let [inspector (swap-inspector! msg inspect/prev-page)]
    (transport/send
     transport
     (response-for msg :value (:rendered inspector) :status :done))))

(defn set-page-size-reply
  [{:keys [page-size transport] :as msg}]
  (let [page-size (Integer/parseInt page-size)
        inspector (swap-inspector! msg inspect/set-page-size page-size)]
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
      "inspect-next-page" (next-page-reply msg)
      "inspect-prev-page" (prev-page-reply msg)
      "inspect-set-page-size" (set-page-size-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-inspect
 (cljs/expects-piggieback
  {:requires #{"clone" #'pr-values}
   :expects #{"eval"}
   :handles {"inspect-pop"
             {:doc "Moves one level up in the inspector stack."
              :requires {"session" "The current session"}
              :returns {"status" "\"done\""}}
             "inspect-push"
             {:doc "Inspects the inside value specified by index."
              :requires {"idx" "Index of the internal value currently rendered."
                         "session" "The current session"}
              :returns {"status" "\"done\""}}
             "inspect-refresh"
             {:doc "Re-renders the currently inspected value."
              :requires {"session" "The current session"}
              :returns {"status" "\"done\""}}
             "inspect-next-page"
             {:doc "Jumps to the next page in paginated collection view."
              :requires {"session" "The current session"}
              :returns {"status" "\"done\""}}
             "inspect-prev-page"
             {:doc "Jumps to the previous page in paginated collection view."
              :requires {"session" "The current session"}
              :returns {"status" "\"done\""}}
             "inspect-set-page-size"
             {:doc "Sets the page size in paginated view to specified value."
              :requires {"page-size" "New page size."
                         "session" "The current session"}
              :returns {"status" "\"done\""}}}}))
