(ns cider.nrepl.middleware.util.storage)

(def ^:private store (ref {}))
(def ^:private exp-index (ref '()))
(def ^:private capacity 10)

(defn add!
  "Adds the provided kv pair to a single-retrieval store with
  queue-capacity expiring behavior; i.e. not only is kv removed from
  the store when sucessfully queried, but if addition of the supplied
  item causes store size to increase over the default capacity of 10
  items, then the oldest kv pair in the store is also
  expired (removed). Returns the value added to the store."
  [k v]
  (dosync
   (let [untrimmed-exp           (conj @exp-index k)
         untrimmed-store         (assoc @store k v)
         [trimmed-exp trimmable] (split-at capacity untrimmed-exp)
         trimmed-store           (apply dissoc untrimmed-store trimmable)]
     (ref-set store trimmed-store)
     (ref-set exp-index trimmed-exp)
     v)))

(defn query!
  "Queries the single-retrieval store. Finds value associated with the
  supplied key `k` and then removes the kv pair from storage. If `k`
  not present, returns nil and no changes are made."
  [k]
  (dosync
   (let [reply (get @store k)]
     (ref-set store (dissoc @store k))
     (ref-set exp-index (remove #(= k %) @exp-index))
     reply)))

(defn refresh!
  "Refreshes the single-retrieval, queue-capacity store. Removes all kv
  pairs from the store."
  []
  (dosync
   (ref-set store {})
   (ref-set exp-index '())))
