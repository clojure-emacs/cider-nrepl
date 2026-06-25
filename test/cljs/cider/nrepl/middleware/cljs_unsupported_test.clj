(ns cider.nrepl.middleware.cljs-unsupported-test
  (:require
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :once piggieback-fixture)

;; Clojure-only ops should reply with a clear `clojure-only` status instead of a
;; confusing failure or a JVM-only result when a ClojureScript REPL is active.
;; See clojure-emacs/cider#2198.

(deftest clojure-only-ops-report-unsupported-under-cljs
  (doseq [msg [{:op "cider/apropos" :query "map"}
               {:op "cider/fn-refs" :ns "cljs.user" :symbol "map"}
               {:op "cider/toggle-trace-var" :ns "cljs.user" :sym "map"}
               {:op "cider/profile-summary"}
               {:op "cider/refresh"}
               {:op "cider.clj-reload/reload"}
               {:op "cider/spec-list"}
               {:op "cider/test-var-query" :ns-query {:exactly ["cljs.user"]}}
               {:op "cider/undef" :ns "cljs.user" :symbol "x"}]]
    (testing (:op msg)
      (let [{:keys [status err]} (session/message msg)]
        (is (contains? status "clojure-only")
            "should report the op as Clojure-only under cljs")
        (is (contains? status "done"))
        (is (re-find #"Clojure-only and isn't available in a ClojureScript REPL" (str err)))))))
