(ns cider.nrepl.test-session
  (:require
   [cider.nrepl :refer [cider-nrepl-handler]]
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.server :refer [start-server]]))

(def ^:dynamic *handler* cider-nrepl-handler)
(def ^:dynamic *session* nil)

(def ^:dynamic ^nrepl.server.Server *server* nil)
(def ^:dynamic ^nrepl.transport.FnTransport *transport* nil)

(defn repl-session!
  "Start an nREPL session and set *session* accordingly.

  Eval'ing this function in the REPL will allow you to test out messages
  with [[message]].

  When dealing with tests that use [[session-fixture]], this can help you to be
  able to evaluate test forms in the REPL. Call [[close-session!]] when you're
  done."
  []
  (let [server    (start-server :handler *handler*)
        transport (nrepl/connect :port (:port server))
        client    (nrepl/client transport Long/MAX_VALUE)]
    (alter-var-root #'*server* (constantly server))
    (alter-var-root #'*transport* (constantly transport))
    (alter-var-root #'*session* (constantly (nrepl/client-session client)))))

(defn close-session!
  "Stop the server/session created by [[repl-session!]], and reset the vars."
  []
  (.close *server*)
  (.close *transport*)
  (alter-var-root #'*server* (constantly nil))
  (alter-var-root #'*transport* (constantly nil))
  (alter-var-root #'*session* (constantly nil)))

(defn session-fixture
  [f]
  (with-open [^nrepl.server.Server
              server    (start-server :handler *handler*)
              ^nrepl.transport.FnTransport
              transport (nrepl/connect :port (:port server))]
    (let [client  (nrepl/client transport Long/MAX_VALUE)
          session (nrepl/client-session client)]
      (binding [*server*    server
                *transport* transport
                *session*   session]
        (f)))))

(defn message
  ([msg] (message msg true))
  ([msg combine-responses?]
   (let [responses (nrepl/message *session* msg)]
     (if combine-responses?
       (nrepl/combine-responses responses)
       responses))))

(use-fixtures :each session-fixture)

(deftest sanity-test
  (testing "eval works"
    (is (= ["(true false true false true false)"]
           (:value (message {:op   "eval"
                             :code (nrepl/code (map even? (range 6)))})))))

  (testing "unsupported op"
    (is (= #{"error" "unknown-op" "done"}
           (:status (message {:op "abcdefg"})))))

  (testing "describe works"
    (let [response (message {:op "describe"})
          verbose-response (message {:op       "describe"
                                     :verbose? "true"})]
      (is (contains? response :ops))
      (is (contains? verbose-response :ops)))))
