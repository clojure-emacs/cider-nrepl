(ns cider.nrepl.middleware.inspect
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [base-error-response]]
            [orchard.inspect :as inspect]
            [orchard.misc :as u]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
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

(defn handle-inspect [handler msg]
  (case (:op msg)
    "eval" (eval-reply handler msg)
    "inspect-pop" (pop-reply msg)
    "inspect-push" (push-reply msg)
    "inspect-refresh" (refresh-reply msg)
    "inspect-get-path" (get-path-reply msg)
    "inspect-next-page" (next-page-reply msg)
    "inspect-prev-page" (prev-page-reply msg)
    "inspect-set-page-size" (set-page-size-reply msg)
    (handler msg)))
