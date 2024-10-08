(ns cider.nrepl.middleware.inspect
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling
    :refer [eval-interceptor-transport with-safe-transport]]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.info :as info]
   [orchard.inspect :as inspect]
   [orchard.java]))

(defn- update-inspector [inspector f & args]
  ;; Ensure that there is valid inspector value before passing it to
  ;; orchard.inspect functions.
  (let [inspector (if (map? inspector)
                    inspector (inspect/start nil))]
    (apply f inspector args)))

(defn swap-inspector!
  [{:keys [session]} f & args]
  (-> session
      (alter-meta! update ::inspector #(apply update-inspector % f args))
      (get ::inspector)))

(defn- extract-doc-fragments
  "If `value` is either a class, a method, or a field, try to calculate doc
  fragments from it and return as a map."
  [value]
  (let [class-sym (when (class? value)
                    (-> ^Class value .getCanonicalName symbol))
        method-sym (when (instance? java.lang.reflect.Method value)
                     (symbol (str (-> ^java.lang.reflect.Method value .getDeclaringClass .getCanonicalName)
                                  "/"
                                  (-> ^java.lang.reflect.Method value .getName))))
        field-sym (when (instance? java.lang.reflect.Field value)
                    (symbol (str (-> ^java.lang.reflect.Field value .getDeclaringClass .getCanonicalName)
                                 "/"
                                 (-> ^java.lang.reflect.Field value .getName))))
        fragments-sym (or method-sym field-sym class-sym)]
    (if fragments-sym
      (select-keys (info/info 'user fragments-sym)
                   [:doc-fragments
                    :doc-first-sentence-fragments
                    :doc-block-tags-fragments])
      {})))

(defn- inspector-response
  ([msg inspector]
   (inspector-response msg inspector {:status :done}))

  ([msg {:keys [rendered value path]} extra-response-data]
   (let [data (binding [*print-length* nil]
                {:value (pr-str (seq rendered))
                 :path (pr-str (seq path))})]
     (response-for msg extra-response-data data (extract-doc-fragments value)))))

(defn- warmup-javadoc-cache [^Class clazz]
  (when-let [class-sym (some-> clazz .getCanonicalName symbol)]
    ;; Don't spawn a `future` for already-computed caches:
    (when-not (get orchard.java/cache class-sym)
      (future ;; TODO: replace future with controlled threadpool.
        ;; Warmup the Orchard cache for the class of the currently inspected
        ;; value. This way, if the user inspects this class next, the underlying
        ;; inspect request will complete quickly.
        (info/info 'user class-sym)
        ;; Same for its implemented interfaces:
        (doseq [^Class interface (.getInterfaces clazz)]
          (info/info 'user (-> interface .getCanonicalName symbol)))))))

(defn- msg->inspector-config [msg]
  (select-keys msg [:page-size :max-atom-length :max-coll-size
                    :max-value-length :max-nested-depth]))

(defn inspect-reply*
  [msg value]
  (let [config (msg->inspector-config msg)
        inspector (swap-inspector! msg #(inspect/start (merge % config) value))]
    (warmup-javadoc-cache (class (:value inspector)))
    ;; By using 3-arity `inspector-response` we ensure that the default
    ;; `{:status :done}` is not sent with this message, as the underlying
    ;; eval will send it on its own.
    (inspector-response msg inspector {})))

(defn inspect-reply
  [msg eval-response]
  (let [value (cljs/response-value msg eval-response)]
    (transport/send (:transport msg)
                    (inspect-reply* msg value))))

(defn handle-eval-inspect [handler msg]
  ;; Let eval command be executed but intercept its :value with `inspect-reply`.
  (handler (assoc msg :transport (eval-interceptor-transport
                                  msg inspect-reply :inspect-eval-error))))

(defn pop-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/up)))

(defn push-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/down (:idx msg))))

(defn next-sibling-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/next-sibling)))

(defn previous-sibling-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/previous-sibling)))

(defn next-page-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/next-page)))

(defn prev-page-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/prev-page)))

(defn refresh-reply [msg]
  (let [overrides (msg->inspector-config msg)]
    (inspector-response msg (swap-inspector! msg #(inspect/refresh % overrides)))))

(defn- toggle-view-mode [{:keys [view-mode] :as inspector}]
  (let [toggle-order {:normal :object, :object :normal}
        next-view-mode (toggle-order view-mode :normal)]
    (inspect/set-view-mode inspector next-view-mode)))

(defn toggle-view-mode-reply [msg]
  (inspector-response msg (swap-inspector! msg toggle-view-mode)))

(defn ^:deprecated set-page-size-reply [msg] (refresh-reply msg))
(defn ^:deprecated set-max-atom-length-reply [msg] (refresh-reply msg))
(defn ^:deprecated set-max-coll-size-reply [msg] (refresh-reply msg))
(defn ^:deprecated set-max-nested-depth-reply [msg] (refresh-reply msg))

(defn clear-reply [msg]
  (inspector-response msg (swap-inspector! msg (constantly (inspect/start nil)))))

(defn def-current-value [msg]
  (inspector-response msg (swap-inspector! msg inspect/def-current-value (symbol (:ns msg)) (:var-name msg))))

(defn tap-current-value [msg]
  (inspector-response msg (swap-inspector! msg inspect/tap-current-value)))

(defn tap-indexed [msg]
  (inspector-response msg (swap-inspector! msg inspect/tap-indexed (:idx msg))))

(defn handle-inspect [handler {:keys [op inspect] :as msg}]
  (if (and (= op "eval") inspect)
    (handle-eval-inspect handler msg)

    (with-safe-transport handler msg
      "inspect-pop" pop-reply
      "inspect-push" push-reply
      "inspect-next-sibling" next-sibling-reply
      "inspect-previous-sibling" previous-sibling-reply
      "inspect-next-page" next-page-reply
      "inspect-prev-page" prev-page-reply
      "inspect-refresh" refresh-reply
      "inspect-toggle-view-mode" toggle-view-mode-reply
      "inspect-set-page-size" refresh-reply
      "inspect-set-max-atom-length" refresh-reply
      "inspect-set-max-coll-size" refresh-reply
      "inspect-set-max-nested-depth" refresh-reply
      "inspect-clear" clear-reply
      "inspect-def-current-value" def-current-value
      "inspect-tap-current-value" tap-current-value
      "inspect-tap-indexed" tap-indexed)))
