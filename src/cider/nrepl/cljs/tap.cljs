(ns cider.nrepl.cljs.tap
  "ClojureScript-runtime helper for the tap middleware.

  Loaded into the user's ClojureScript runtime on demand (via `require`) by
  `cider.nrepl.middleware.tap`.  A JS runtime can't push to nREPL on its own, so
  this registers an `add-tap' handler that accumulates an EDN-safe summary of
  each tapped value into an atom; the JVM side drains it by polling.")

(defonce ^:private buffer (atom []))

(defonce ^:private tap-fn (atom nil))

(defn- type-name
  "A short, readable type label for VALUE."
  [value]
  (cond
    (nil? value) "nil"
    (map? value) "map"
    (vector? value) "vector"
    (set? value) "set"
    (seq? value) "seq"
    (list? value) "list"
    (string? value) "string"
    (keyword? value) "keyword"
    (symbol? value) "symbol"
    (number? value) "number"
    (boolean? value) "boolean"
    :else (let [t (type value)]
            (or (some-> t .-name) (str t)))))

(defn- summarize
  "Return an EDN-safe summary of a tapped VALUE."
  [value]
  (let [s (binding [*print-length* 8 *print-level* 4] (pr-str value))
        max-len 200]
    (cond-> {:summary (if (> (count s) max-len)
                        (str (subs s 0 max-len) " …")
                        s)
             :type (type-name value)}
      (counted? value) (assoc :count (count value)))))

(defn drain
  "Return the summaries accumulated since the last call, clearing the buffer.
  Runs synchronously on the single JS thread, so the read and clear can't
  interleave with a tap delivery."
  []
  (let [old @buffer]
    (when (seq old)
      (swap! buffer subvec (count old)))
    old))

(defn start!
  "Register the tap handler (idempotent)."
  []
  (when-not @tap-fn
    (let [f (fn [value] (swap! buffer conj (summarize value)))]
      (reset! tap-fn f)
      (add-tap f)))
  nil)

(defn stop!
  "Remove the tap handler and clear the buffer."
  []
  (when-let [f @tap-fn]
    (remove-tap f)
    (reset! tap-fn nil))
  (reset! buffer [])
  nil)
