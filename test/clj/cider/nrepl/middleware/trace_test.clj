(ns cider.nrepl.middleware.trace-test
  (:require
   [cider.nrepl.middleware.trace :refer :all]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [nrepl.transport :as transport]
   [cider.test-ns.first-test-ns]))

(use-fixtures :each session/session-fixture)

(defn traced-sample
  "A sample fn to trace; nothing else calls it, so its events are predictable."
  [x]
  (inc x))

(deftest toggle-trace-var-test
  (testing "toggling"
    (is (= {:var-name "#'clojure.core/zipmap" :var-status "traced"}
           (toggle-trace-var {:ns "clojure.core" :sym "zipmap"})))
    (is (= {:var-name "#'clojure.core/zipmap" :var-status "untraced"}
           (toggle-trace-var {:ns "clojure.core" :sym "zipmap"}))))

  (testing "misses"
    (testing "toggle-trace-var-op unresolvable, should return `not-found`"
      (is (= {:var-status "not-found" :status #{:toggle-trace-error :done}}
             (toggle-trace-var {:ns "clojure.core" :sym "mappp"}))))

    (testing "toggle-trace-var-op not traceable var, should return `not-traceable`"
      (is (= {:var-name "#'clojure.core/and" :var-status "not-traceable"}
             (toggle-trace-var {:ns "clojure.core" :sym "and"}))))))

(deftest toggle-trace-ns-test
  (testing "toggling"
    (is (= {:ns-status "traced"}
           (toggle-trace-ns {:ns "orchard.inspect"})))
    (is (= {:ns-status "untraced"}
           (toggle-trace-ns {:ns "orchard.inspect"}))))

  (testing "toggle-trace-ns-op missing ns should return `not-found`"
    (is (= {:ns-status "not-found"}
           (toggle-trace-ns {:ns "clojure.corex"})))))

(deftest integration-tests-var
  (testing "toggling"
    (let [on  (session/message {:op "cider/toggle-trace-var"
                                :ns "cider.test-ns.first-test-ns"
                                :sym "same-name-testing-function"})
          off (session/message {:op "cider/toggle-trace-var"
                                :ns "cider.test-ns.first-test-ns"
                                :sym "same-name-testing-function"})]
      (is+ {:status #{"done"}
            :var-name "#'cider.test-ns.first-test-ns/same-name-testing-function"
            :var-status "traced"}
           on)
      (is+ {:status #{"done"}
            :var-name "#'cider.test-ns.first-test-ns/same-name-testing-function"
            :var-status "untraced"}
           off)))

  (testing "unresolvable"
    (let [var-err (session/message {:op "cider/toggle-trace-var"
                                    :ns "cider.test-ns.first-test-ns"
                                    :sym "missing"})
          ns-err  (session/message {:op "cider/toggle-trace-var"
                                    :ns "cider.test-ns.no-such-ns"
                                    :sym "same-name-testing-function"})]
      (is+ {:status #{"toggle-trace-error" "done"}
            :var-status "not-found"}
           var-err)
      (is+ {:status #{"toggle-trace-error" "done"}}
           ns-err))))

(deftest integration-test-ns
  (testing "toggling"
    (let [on  (session/message {:op "cider/toggle-trace-ns"
                                :ns "cider.test-ns.first-test-ns"})
          off (session/message {:op "cider/toggle-trace-ns"
                                :ns "cider.test-ns.first-test-ns"})]
      (is+ {:status #{"done"} :ns-status "traced"} on)
      (is+ {:status #{"done"} :ns-status "untraced"} off))

    (let [ns-err (session/message {:op "cider/toggle-trace-ns"
                                   :ns "cider.test-ns.missing"})]
      (is+ {:status #{"done"} :ns-status "not-found"} ns-err))))

(deftest list-traced-test
  (testing "lists the currently traced vars and namespaces"
    (untrace-all {}) ; start from a clean slate
    (is (= {:traced-vars [] :traced-nses []} (list-traced {})))
    (toggle-trace-var {:ns "clojure.core" :sym "zipmap"})
    (is (= ["#'clojure.core/zipmap"] (:traced-vars (list-traced {}))))
    (untrace-all {})))

(deftest untrace-all-test
  (testing "untraces every var and namespace and reports the count"
    (untrace-all {}) ; start from a clean slate
    (toggle-trace-var {:ns "clojure.core" :sym "zipmap"})
    (toggle-trace-ns {:ns "cider.test-ns.first-test-ns"})
    (is (pos? (:untraced-count (untrace-all {}))))
    (is (= {:traced-vars [] :traced-nses []} (list-traced {})))))

(deftest integration-test-list-and-untrace-all
  (testing "listing and clearing traces over the session"
    (session/message {:op "cider/untrace-all"}) ; clean slate
    (session/message {:op "cider/toggle-trace-var"
                      :ns "cider.test-ns.first-test-ns"
                      :sym "same-name-testing-function"})
    (let [listed (session/message {:op "cider/list-traced"})]
      (is+ {:status #{"done"}
            :traced-vars ["#'cider.test-ns.first-test-ns/same-name-testing-function"]}
           listed))
    (let [cleared (session/message {:op "cider/untrace-all"})]
      (is+ {:status #{"done"} :untraced-count 1} cleared))
    (let [listed (session/message {:op "cider/list-traced"})]
      (is+ {:status #{"done"}
            :traced-vars empty?
            :traced-nses empty?}
           listed))))

(deftest trace-subscribe-test
  (testing "subscribe streams events for traced calls, unsubscribe stops them"
    (let [sent      (atom [])
          transport (reify transport/Transport
                      (send [this msg] (swap! sent conj msg) this)
                      (recv [_this] nil)
                      (recv [_this _timeout] nil))
          {id :cider/trace-subscribe} (trace-subscribe {:transport transport
                                                        :id "42" :session "s"})]
      (try
        (is (string? id))
        (toggle-trace-var {:ns "cider.nrepl.middleware.trace-test" :sym "traced-sample"})
        (traced-sample 1)
        (let [events (keep :cider/trace-event @sent)]
          (is (= 2 (count events)) "one call event and one return event")
          (is (= ["call" "return"] (map :phase events)))
          (is (every? #(= "cider.nrepl.middleware.trace-test/traced-sample" (:name %)) events))
          (is (= (:id (first events)) (:id (second events))) "call and return share an id"))
        (finally
          (toggle-trace-var {:ns "cider.nrepl.middleware.trace-test" :sym "traced-sample"})
          (trace-unsubscribe {:subscription id})))
      ;; after unsubscribe the listener is gone, so a further traced call streams nothing
      (reset! sent [])
      (toggle-trace-var {:ns "cider.nrepl.middleware.trace-test" :sym "traced-sample"})
      (with-out-str (traced-sample 1)) ; output mode is back to :repl, swallow its print
      (is (empty? (keep :cider/trace-event @sent)))
      (toggle-trace-var {:ns "cider.nrepl.middleware.trace-test" :sym "traced-sample"}))))

(deftest deprecated-ops-test
  (testing "Deprecated 'toggle-trace-var' op still works"
    (let [on  (session/message {:op "toggle-trace-var"
                                :ns "cider.test-ns.first-test-ns"
                                :sym "same-name-testing-function"})
          off (session/message {:op "toggle-trace-var"
                                :ns "cider.test-ns.first-test-ns"
                                :sym "same-name-testing-function"})]
      (is+ {:status #{"done"} :var-status "traced"} on)
      (is+ {:status #{"done"} :var-status "untraced"} off)))

  (testing "Deprecated 'toggle-trace-ns' op still works"
    (let [on  (session/message {:op "toggle-trace-ns"
                                :ns "cider.test-ns.first-test-ns"})
          off (session/message {:op "toggle-trace-ns"
                                :ns "cider.test-ns.first-test-ns"})]
      (is+ {:status #{"done"} :ns-status "traced"} on)
      (is+ {:status #{"done"} :ns-status "untraced"} off))))
