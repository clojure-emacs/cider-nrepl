(ns cider.nrepl.middleware.inspect
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [base-error-response
                                                       with-safe-transport]]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.inspect :as inspect])
  (:import
   nrepl.transport.Transport))

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
    (binding [*print-length* nil]
      ;; Remove print-length limit because it breaks the output in the middle of
      ;; the page when inspecting long sequences.
      (transport/send
       transport
       (response-for msg :value (:rendered inspector))))))

(defn inspector-transport
  [{:keys [^Transport transport, session] :as msg}]
  (reify Transport
    (recv [this] (.recv transport))
    (recv [this timeout] (.recv transport timeout))
    (send [this response]
      (cond (contains? response :value)
            (inspect-reply msg response)

            ;; If the eval errored, propagate the exception as error in the
            ;; inspector middleware, so that the client CIDER code properly
            ;; renders it instead of silently ignoring it.
            (contains? (:status response) :eval-error)
            (let [e (or (@session #'*e)
                        (Exception. (or (:ex response) "")))
                  resp (base-error-response msg e :inspect-eval-error :done)]
              (.send transport resp))

            :else (.send transport response))
      this)))

(defn eval-msg
  [{:keys [inspect] :as msg}]
  (if inspect
    (assoc msg :transport (inspector-transport msg))
    msg))

(defn eval-reply
  [handler msg]
  (handler (eval-msg msg)))

(defn- inspector-response [msg inspector]
  (response-for msg :value (:rendered inspector) :status :done))

(defn pop-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/up)))

(defn push-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/down (:idx msg))))

(defn refresh-reply [msg]
  (inspector-response msg (swap-inspector! msg #(or % (inspect/fresh)))))

(defn get-path-reply [{:keys [session] :as msg}]
  (:path (get session #'*inspector*)))

(defn next-page-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/next-page)))

(defn prev-page-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/prev-page)))

(defn set-page-size-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/set-page-size (:page-size msg))))

(defn handle-inspect [handler msg]
  (if (= (:op msg) "eval")
    (eval-reply handler msg)

    (with-safe-transport handler msg
      "inspect-pop" pop-reply
      "inspect-push" push-reply
      "inspect-refresh" refresh-reply
      "inspect-get-path" get-path-reply
      "inspect-next-page" next-page-reply
      "inspect-prev-page" prev-page-reply
      "inspect-set-page-size" set-page-size-reply)))
