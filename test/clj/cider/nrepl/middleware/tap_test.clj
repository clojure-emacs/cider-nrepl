(ns cider.nrepl.middleware.tap-test
  (:require
   [cider.nrepl.middleware.tap :refer :all]
   [clojure.test :refer :all]
   [nrepl.transport :as transport]))

(defn- wait-for
  "Poll PRED (for up to ~1s) until it returns truthy; return that or nil.
  `tap>' delivers values asynchronously on a dedicated thread, so tests have
  to wait for the streamed message rather than assert immediately."
  [pred]
  (loop [n 0]
    (or (pred)
        (when (< n 100)
          (Thread/sleep 10)
          (recur (inc n))))))

(defn- capturing-transport [sent]
  (reify transport/Transport
    (send [this msg] (swap! sent conj msg) this)
    (recv [_this] nil)
    (recv [_this _timeout] nil)))

(deftest tap-subscribe-test
  (testing "subscribe streams tapped values; unsubscribe stops them"
    (let [sent (atom [])
          {id :cider/tap-subscribe} (tap-subscribe {:transport (capturing-transport sent)
                                                    :id "1" :session "s"})]
      (try
        (is (string? id))
        (tap> {:hello 42})
        (is (wait-for #(seq (keep :cider/tap-value @sent)))
            "the tapped value was streamed")
        (let [event (first (keep :cider/tap-value @sent))]
          (is (string? (:summary event)))
          (is (re-find #":hello" (:summary event)))
          (is (= "clojure.lang.PersistentArrayMap" (:type event)))
          (is (= 1 (:count event)))
          (is (integer? (:idx event)))

          (testing "tap-inspect renders a retained value by its idx"
            (let [resp (tap-inspect {:idx (str (:idx event)) :session (atom {}) :id "2"})]
              (is (string? (:value resp)))
              (is (re-find #"42" (:value resp)))))

          (testing "tap-inspect on an unknown idx reports it isn't retained"
            (is (= #{:tap-value-not-retained}
                   (:status (tap-inspect {:idx "999999" :session (atom {}) :id "3"}))))))
        (finally
          (tap-unsubscribe {:subscription id})))

      (testing "after unsubscribe no further values are streamed"
        (reset! sent [])
        (tap> {:ignored true})
        (is (nil? (wait-for #(seq (keep :cider/tap-value @sent)))))))))

(deftest tap-subscribe-dead-transport-test
  (testing "a subscription whose transport fails is pruned"
    (let [transport (reify transport/Transport
                      (send [_this _msg]
                        (throw (java.net.SocketException. "Socket closed")))
                      (recv [_this] nil)
                      (recv [_this _timeout] nil))
          {id :cider/tap-subscribe} (tap-subscribe {:transport transport
                                                    :id "1" :session "s"})]
      (try
        (tap> :doomed)
        (is (wait-for #(not (contains? @@#'cider.nrepl.middleware.tap/subscriptions id)))
            "the dead subscription was pruned")
        (finally
          (tap-unsubscribe {:subscription id}))))))
