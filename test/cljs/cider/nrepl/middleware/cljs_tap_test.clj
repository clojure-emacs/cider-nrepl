(ns cider.nrepl.middleware.cljs-tap-test
  (:require
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :once piggieback-fixture)

;; The runtime helper (`cider.nrepl.cljs.tap') is loaded into and run inside the
;; ClojureScript runtime, so it's referenced via string `:code' rather than as a
;; required (JVM) namespace.

(deftest cljs-tap-runtime-helper-test
  ;; The JVM side can't see cljs taps, so it polls this runtime helper.  Exercise
  ;; the helper end-to-end in the node runtime: register the tap, `tap>` a value,
  ;; and drain an EDN-safe summary.
  (session/message {:op "eval" :code "(require 'cider.nrepl.cljs.tap)"})
  (session/message {:op "eval" :code "(cider.nrepl.cljs.tap/start!)"})
  (session/message {:op "eval" :code "(tap> {:hello 42})"})
  ;; `tap>` dispatches on the next event-loop tick (setTimeout 0); a further
  ;; round-trip to the runtime lets that fire before we drain.
  (session/message {:op "eval" :code ":flush"})
  (testing "a tapped value is buffered and drained as a summary"
    (let [value (first (:value (session/message
                                {:op "eval" :code "(cider.nrepl.cljs.tap/drain)"})))]
      (is (re-find #":hello 42" value))
      (is (re-find #"\"map\"" value))))
  (testing "drain clears the buffer"
    (let [value (first (:value (session/message
                                {:op "eval" :code "(cider.nrepl.cljs.tap/drain)"})))]
      (is (= "[]" value))))
  (testing "stop! removes the handler so further taps aren't buffered"
    (session/message {:op "eval" :code "(cider.nrepl.cljs.tap/stop!)"})
    (session/message {:op "eval" :code "(tap> :ignored)"})
    (session/message {:op "eval" :code ":flush"})
    (let [value (first (:value (session/message
                                {:op "eval" :code "(cider.nrepl.cljs.tap/drain)"})))]
      (is (= "[]" value)))))
