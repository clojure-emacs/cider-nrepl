(ns cider.nrepl.middleware.tap
  "Stream values sent to `tap>' to the client, and let it inspect them.

  A subscription registers an `add-tap' handler that, for each tapped value,
  retains it (bounded, for later inspection) and forwards a short summary to the
  client on the subscribing request - mirroring how the trace middleware streams
  events.  `cider/tap-inspect' starts a normal inspector session on a retained
  value by its index, so the client reuses the existing inspector UI."
  (:require
   [cider.nrepl.middleware.inspect :as inspect]
   [cider.nrepl.middleware.tap.cljs :as tap-cljs]
   [cider.nrepl.middleware.util :refer [respond-to]]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]])
  (:import
   [java.util UUID]))

(def ^:private max-retained-values
  "How many recently tapped values to keep available for `cider/tap-inspect'."
  100)

(def ^:private retained
  "Recently tapped values available for inspection.
  `:vals' maps an integer index to a value; `:order' is the FIFO of indices used
  to evict the oldest once `max-retained-values' is exceeded."
  (atom {:next 0, :vals {}, :order clojure.lang.PersistentQueue/EMPTY}))

(defn- retain!
  "Store VALUE for later inspection and return the index assigned to it."
  [value]
  (-> (swap! retained
             (fn [{:keys [next vals order]}]
               (let [vals  (assoc vals next value)
                     order (conj order next)
                     over? (> (count vals) max-retained-values)]
                 {:next  (inc next)
                  :vals  (if over? (dissoc vals (peek order)) vals)
                  :order (if over? (pop order) order)})))
      :next
      dec))

(defn- summarize
  "Return a short, bencode-friendly description of a tapped VALUE."
  [value]
  (let [s   (binding [*print-length* 8, *print-level* 4] (pr-str value))
        max 200]
    (cond-> {:summary (if (> (count s) max)
                        (str (subs s 0 max) " …")
                        s)
             :type    (if (nil? value) "nil" (.getName (class value)))}
      (counted? value) (assoc :count (count value)))))

(def ^:private subscriptions
  "Map of subscription id -> the `add-tap' handler registered for it."
  (atom {}))

(defn tap-subscribe
  "Stream a summary of every tapped value to the client until
  `cider/tap-unsubscribe'.  Registers an `add-tap' handler that forwards each
  value on this request and retains it for `cider/tap-inspect'."
  [msg]
  (let [id     (str (UUID/randomUUID))
        tap-fn (fn [value]
                 (let [idx (retain! value)]
                   (respond-to msg
                               :cider/tap-value (assoc (summarize value) :idx idx)
                               :status :cider/tap-value)))]
    (swap! subscriptions assoc id tap-fn)
    (add-tap tap-fn)
    {:cider/tap-subscribe id}))

(defn tap-unsubscribe
  "Stop streaming tapped values for the given subscription."
  [{:keys [subscription]}]
  (when-let [tap-fn (get @subscriptions subscription)]
    (remove-tap tap-fn)
    (swap! subscriptions dissoc subscription))
  {:cider/tap-unsubscribe subscription})

(defn tap-inspect
  "Start an inspector session on the retained tapped value at index `idx'."
  [{:keys [idx] :as msg}]
  (let [idx  (some-> idx Long/parseLong)
        vals (:vals @retained)]
    (if (and idx (contains? vals idx))
      ;; Reuse the inspector: this sets the session inspector and returns the
      ;; standard inspector response, so all later inspect ops keep working.
      (inspect/inspect-reply* msg (get vals idx))
      {:status #{:tap-value-not-retained}})))

(defn handle-tap [handler msg]
  ;; In a ClojureScript REPL the JVM `add-tap' can't see taps in the JS runtime,
  ;; so subscribe/unsubscribe are handled by polling the runtime (it streams and
  ;; replies on its own).  `tap-inspect' stays on the Clojure path - cljs taps
  ;; carry no `idx' and aren't inspectable yet.
  (if (and (cljs/grab-cljs-env msg)
           (#{"cider/tap-subscribe" "cider/tap-unsubscribe"} (:op msg)))
    (tap-cljs/handle handler msg)
    (with-safe-transport handler msg
      {"cider/tap-subscribe"   [tap-subscribe :tap-subscribe-error]
       "cider/tap-unsubscribe" [tap-unsubscribe :tap-unsubscribe-error]
       "cider/tap-inspect"     [tap-inspect :tap-inspect-error]})))
