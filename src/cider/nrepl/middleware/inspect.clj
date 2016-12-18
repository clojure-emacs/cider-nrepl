(ns cider.nrepl.middleware.inspect
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [base-error-response]]
            [cider.nrepl.middleware.util.inspect :as inspect]
            [cider.nrepl.middleware.util.misc :as u]
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
  [{:keys [page-size transport] :as msg} eval-response]
  (let [value (cljs/response-value msg eval-response)
        page-size (or page-size 32)
        inspector (swap-inspector! msg #(-> (assoc % :page-size page-size)
                                            (inspect/start value)))]
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

(defn- success [{:keys [transport] :as msg} inspector]
  (transport/send transport (response-for msg :value (:rendered inspector) :status :done)))

(defn- failure [{:keys [transport] :as msg} err err-kw]
  (transport/send transport (base-error-response msg err err-kw :done)))

(defn pop-reply [msg]
  (try (success msg (swap-inspector! msg inspect/up))
       (catch Exception e (failure msg e :inspect-pop-error))))

(defn push-reply [msg]
  (try (success msg (swap-inspector! msg inspect/down (:idx msg)))
       (catch Exception e (failure msg e :inspect-push-error))))

(defn refresh-reply [msg]
  (try (success msg (swap-inspector! msg #(or % (inspect/fresh))))
       (catch Exception e (failure msg e :inspect-refresh-error))))

(defn get-path-reply [{:keys [session] :as msg}]
  (try (success msg (:path (get session #'*inspector*)))
       (catch Exception e (failure msg e :inspect-get-path-error))))

(defn next-page-reply [msg]
  (try (success msg (swap-inspector! msg inspect/next-page))
       (catch Exception e (failure msg e :inspect-next-page-error))))

(defn prev-page-reply [msg]
  (try (success msg (swap-inspector! msg inspect/prev-page))
       (catch Exception e (failure msg e :inspect-prev-page-error))))

(defn set-page-size-reply [msg]
  (try (success msg (swap-inspector! msg inspect/set-page-size (:page-size msg)))
       (catch Exception e (failure msg e :inspect-set-page-size-error))))

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
      "inspect-get-path" (get-path-reply msg)
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
             "inspect-get-path"
             {:doc "Returns the path to the current position in the inspected value."
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
