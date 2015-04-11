(ns cider.nrepl.test-session
  (:require [cider.nrepl :refer [cider-nrepl-handler]]
            [clojure.test :refer :all]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.server :as server]
            [clojure.tools.nrepl.transport :as transport]))

(def ^:dynamic *handler* cider-nrepl-handler)
(def ^:dynamic *session* nil)

(defn session-fixture
  [f]
  (with-open [server (server/start-server :handler *handler*)
              transport (nrepl/connect :port (:port server))]
    (let [client (nrepl/client transport Long/MAX_VALUE)]
      (binding [*session* (nrepl/client-session client)]
        (f)))))

(defn message
  [msg]
  (nrepl/combine-responses (nrepl/message *session* msg)))

(use-fixtures :each session-fixture)

(deftest sanity
  (testing "eval works"
    (is (= ["(true false true false true false)"]
           (:value (message {:op :eval
                             :code (nrepl/code (map even? (range 6)))})))))

  (testing "unsupported op"
    (is (= #{"error" "unknown-op" "done"}
           (:status (message {:op "abcdefg"}))))))
