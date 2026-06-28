(ns cider.nrepl.middleware.tap.cljs
  "ClojureScript support for the tap middleware.

  The JVM `add-tap' can't see `tap>' in a JS runtime, so for a ClojureScript
  REPL we load a runtime helper (`cider.nrepl.cljs.tap') that buffers tapped
  values, and poll it via `eval' - the same approach the cljs test middleware
  uses to await async tests.

  Streaming only: a tapped ClojureScript value lives in the JS runtime, so it
  can't be handed to the JVM inspector.  So cljs tap entries carry no `idx' and
  aren't inspectable (a later phase could inspect them in the runtime)."
  (:require
   [cider.nrepl.middleware.util :refer [respond-to]]
   [cider.nrepl.middleware.util.cljs :as cljs])
  (:import
   (java.util UUID)
   (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)
   (nrepl.transport Transport)))

(def ^:private poll-interval-ms 300)

(defonce ^:private ^ScheduledExecutorService poll-executor
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [_ r] (doto (Thread. ^Runnable r "cider-cljs-tap-poll")
                        (.setDaemon true))))))

(def ^:private subscriptions
  "Map of subscription id -> a `running?' atom controlling its poll loop."
  (atom {}))

(defn- eval-cljs
  "Send `code' to be evaluated in the ClojureScript runtime, using `transport' to
  intercept the result."
  [handler msg code transport]
  (handler (assoc msg
                  :op "eval"
                  :code code
                  :transport transport
                  :nrepl.middleware.print/keys [])))

(defn- swallowing-transport
  "A transport that discards an eval's responses and calls `on-done' once it
  completes (used for the helper-loading evals)."
  [{:keys [^Transport transport] :as _msg} on-done]
  (reify Transport
    (recv [_this] (.recv transport))
    (recv [_this timeout] (.recv transport timeout))
    (send [this response]
      (when (contains? (:status response) :done)
        (on-done))
      this)))

(defn- value-transport
  "A transport that forwards `:out'/`:err', hands the evaluated value to
  `on-value', and calls `on-error' on an eval error or a value-less completion."
  [{:keys [^Transport transport] :as msg} on-value on-error]
  (let [value-seen? (volatile! false)]
    (reify Transport
      (recv [_this] (.recv transport))
      (recv [_this timeout] (.recv transport timeout))
      (send [this response]
        (cond
          (contains? response :value)
          (do (vreset! value-seen? true)
              (on-value (cljs/response-value msg response)))

          (or (contains? response :out) (contains? response :err))
          (.send transport response)

          (contains? (:status response) :done)
          (when-not @value-seen? (on-error)))
        this))))

(defn- poll!
  "Drain the runtime's tap buffer once, stream the entries, and reschedule."
  [handler msg running?]
  (when @running?
    (eval-cljs
     handler msg "(cider.nrepl.cljs.tap/drain)"
     (value-transport
      msg
      (fn [entries]
        (when @running?
          (doseq [e entries]
            (respond-to msg :cider/tap-value e :status :cider/tap-value))
          (.schedule poll-executor
                     ^Runnable (fn [] (poll! handler msg running?))
                     (long poll-interval-ms) TimeUnit/MILLISECONDS)))
      ;; Stop polling if the runtime eval errors (e.g. the REPL went away).
      (fn [] (reset! running? false))))))

(defn- subscribe [handler msg]
  (let [id (str (UUID/randomUUID))
        running? (atom true)]
    (swap! subscriptions assoc id running?)
    ;; cljs needs the `require' in its own eval before the namespace's vars can
    ;; be used, so load the helper, register the tap, then start polling.
    (eval-cljs
     handler msg "(require 'cider.nrepl.cljs.tap)"
     (swallowing-transport
      msg
      (fn []
        (eval-cljs
         handler msg "(cider.nrepl.cljs.tap/start!)"
         (swallowing-transport
          msg
          (fn [] (poll! handler msg running?)))))))
    (respond-to msg :cider/tap-subscribe id :status #{:done})))

(defn- unsubscribe [handler {:keys [subscription] :as msg}]
  (when-let [running? (get @subscriptions subscription)]
    (reset! running? false)
    (swap! subscriptions dissoc subscription)
    (eval-cljs handler msg "(cider.nrepl.cljs.tap/stop!)"
               (swallowing-transport msg (fn []))))
  (respond-to msg :cider/tap-unsubscribe subscription :status #{:done}))

(defn handle
  "Handle a tap subscribe/unsubscribe op against a ClojureScript REPL.
  Replies on its own (the stream and the terminal `:done' are sent here, not via
  `with-safe-transport')."
  [handler msg]
  (case (:op msg)
    "cider/tap-subscribe" (subscribe handler msg)
    "cider/tap-unsubscribe" (unsubscribe handler msg)))
