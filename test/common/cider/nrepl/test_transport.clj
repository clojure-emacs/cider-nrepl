(ns cider.nrepl.test-transport
  "A transport for testing"
  (:require
   [nrepl.transport :refer [Transport]]))

(defrecord TestTransport [msgs]
  Transport
  (recv [_] nil)
  (send [_ msg] (swap! msgs conj (dissoc msg :transport))))

(defn test-transport []
  (TestTransport. (atom [])))

(defn messages [test-transport]
  @(:msgs test-transport))
