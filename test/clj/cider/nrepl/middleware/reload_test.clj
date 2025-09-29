(ns cider.nrepl.middleware.reload-test
  (:require
   [cider.nrepl.middleware.reload :as rl]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]))

(use-fixtures :each session/session-fixture)

(def ^:private dirs-to-reload
  ;; Limit the scope of what we reload, because (for example) reloading the
  ;; cider.nrepl.middleware.test-session ns causes *session* in that ns to be
  ;; unloaded, which breaks session-fixture, and hence all of the below tests.
  ["test/clj/cider/nrepl/middleware/util"])

;; Calling init from reload ns to work around mrandersonized version
;; See cider.nrepl.middleware.refresh-test for another test that suffers from this.
(#'rl/init dirs-to-reload)

(deftest user-reload
  (testing "returns fallback if clojure.tools.namespace isn't loaded"
    (with-redefs [resolve (constantly nil)]
      (is (= :foo (#'rl/user-reload 'reload :foo))))))

(deftest reload-op-test
  (testing "reload op works"
    (let [response (session/message {:op "cider.clj-reload/reload"})]
      ;; There is nothing to reload since the files did not change,
      ;; but the message does come from clj-reload.core/reload.
      ;; It's two separate messages, but in (:progress response) they are
      ;; concatenated.
      (is+ {:progress #"Reloading 0 namespaces\.\.\.Reloaded 0 namespaces in \d+ ms"
            :status #{"done" "ok"}}
           response))))

(deftest reload-all-op-test
  (testing "reload-all op works"
    (is+ {:progress (mc/all-of #"Reloading 3 namespaces"
                               #"Reloaded 3 namespaces in \d+ ms")
          :status #{"done" "ok"}}
         (session/message {:op "cider.clj-reload/reload-all"}))))

(deftest reload-clear-op-test
  (testing "reload-all op works"
    (is+ {:progress "Reloading 0 namespaces..."
          :status #{"done" "ok"}}
         (session/message {:op "cider.clj-reload/reload-clear"}))))
