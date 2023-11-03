(ns cider.nrepl.middleware.inspect
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [base-error-response
                                                       with-safe-transport]]
   [nrepl.middleware.caught :as caught]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.info :as info]
   [orchard.inspect :as inspect])
  (:import
   nrepl.transport.Transport))

(defn swap-inspector!
  [{:keys [session]} f & args]
  (-> session
      (alter-meta! update ::inspector #(apply f % args))
      (get ::inspector)))

(defn- inspector-response
  ([msg inspector]
   (inspector-response msg inspector {:status :done}))
  ([msg {:keys [rendered]} resp]
   (let [value (binding [*print-length* nil]
                 (pr-str rendered))]
     (response-for msg resp {:value value}))))

(defn inspect-reply*
  [{:keys [page-size max-atom-length max-coll-size] :as msg} value]
  (let [page-size (or page-size 32)
        {inspector-value :value
         :as inspector} (swap-inspector! msg #(-> %
                                                  (assoc :page-size page-size
                                                         :max-atom-length max-atom-length
                                                         :max-coll-size max-coll-size)
                                                  (inspect/start value)))]
    (when-let [^Class inspector-value-class (class inspector-value)]
      (future
        ;; Warmup the Orchard cache for the class of the currently inspected value.
        ;; This way, if the user inspects this class next,
        ;; the underlying inspect request will complete quickly.
        (info/info 'user
                   (-> inspector-value-class .getCanonicalName symbol))))
    (inspector-response msg inspector {})))

(defn inspect-reply
  [msg eval-response]
  (let [value (cljs/response-value msg eval-response)]
    (transport/send (:transport msg)
                    (inspect-reply* msg value))))

(defn inspector-transport
  [{:keys [^Transport transport] :as msg}]
  (reify Transport
    (recv [_this]
      (.recv transport))

    (recv [_this timeout]
      (.recv transport timeout))

    (send [this response]
      (cond (contains? response :value)
            (inspect-reply msg response)

            ;; If the eval errored, propagate the exception as error in the
            ;; inspector middleware, so that the client CIDER code properly
            ;; renders it instead of silently ignoring it.
            (and (contains? (:status response) :eval-error)
                 (contains? response ::caught/throwable))
            (let [e (::caught/throwable response)
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

(defn pop-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/up)))

(defn push-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/down (:idx msg))))

(defn next-sibling-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/next-sibling)))

(defn previous-sibling-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/previous-sibling)))

(defn refresh-reply [msg]
  (inspector-response msg (swap-inspector! msg #(or % (inspect/fresh)))))

(defn get-path-reply [{:keys [session]}]
  (get-in (meta session) [::inspector :path]))

(defn next-page-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/next-page)))

(defn prev-page-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/prev-page)))

(defn set-page-size-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/set-page-size (:page-size msg))))

(defn set-max-atom-length-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/set-max-atom-length
                                           (:max-atom-length msg))))

(defn set-max-coll-size-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/set-max-coll-size
                                           (:max-coll-size msg))))

(defn clear-reply [msg]
  (inspector-response msg (swap-inspector! msg (constantly (inspect/fresh)))))

(defn def-current-value [msg]
  (inspector-response msg (swap-inspector! msg inspect/def-current-value (symbol (:ns msg)) (:var-name msg))))

(defn tap-current-value [msg]
  (inspector-response msg (swap-inspector! msg inspect/tap-current-value)))

(defn handle-inspect [handler msg]
  (if (= (:op msg) "eval")
    (eval-reply handler msg)

    (with-safe-transport handler msg
      "inspect-pop" pop-reply
      "inspect-push" push-reply
      "inspect-next-sibling" next-sibling-reply
      "inspect-previous-sibling" previous-sibling-reply
      "inspect-refresh" refresh-reply
      "inspect-get-path" get-path-reply
      "inspect-next-page" next-page-reply
      "inspect-prev-page" prev-page-reply
      "inspect-set-page-size" set-page-size-reply
      "inspect-set-max-atom-length" set-max-atom-length-reply
      "inspect-set-max-coll-size" set-max-coll-size-reply
      "inspect-clear" clear-reply
      "inspect-def-current-value" def-current-value
      "inspect-tap-current-value" tap-current-value)))
