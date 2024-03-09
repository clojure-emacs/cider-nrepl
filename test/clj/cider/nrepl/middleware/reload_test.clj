(ns cider.nrepl.middleware.reload-test
  (:require
   [cider.nrepl.middleware.reload :as rl]
   [cider.nrepl.test-session :as session]
   [clojure.string :as str]
   [clojure.test :refer :all]))

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
      (is (= "Nothing to unloadNothing to reload" (:progress response)))
      (is (= #{"done" "ok"} (:status response))))))

(deftest reload-all-op-test
  (testing "reload-all op works"
    (let [response (session/message {:op "cider.clj-reload/reload-all"})
          progress-str (:progress response)]
      (is (str/includes? progress-str "Unloading cider.nrepl.middleware.util.meta-test"))
      (is (str/includes? progress-str "Loading cider.nrepl.middleware.util.meta-test"))
      (is (= #{"done" "ok"} (:status response))))))

(deftest reload-clear-op-test
  (testing "reload-all op works"
    (let [response (session/message {:op "cider.clj-reload/reload-clear"})]
      (is (seq (:progress response)))
      (is (= "Nothing to unload" (:progress response)))
      (is (= #{"done" "ok"} (:status response))))))

