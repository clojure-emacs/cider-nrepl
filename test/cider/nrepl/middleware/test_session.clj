(ns cider.nrepl.middleware.test-session
  (:require [cider.nrepl :refer [cider-nrepl-handler]]
            [clojure.test :refer :all]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.server :as server]
            [clojure.tools.nrepl.transport :as transport]))

(def ^:dynamic *session* nil)

(defn session-fixture
  [f]
  (with-open [server (server/start-server :handler cider-nrepl-handler)
              transport (nrepl/connect :port (:port server))]
    (let [client (nrepl/client transport 1000)]
      (binding [*session* (nrepl/client-session client)]
        (f)))))

(defn message
  [msg]
  (nrepl/combine-responses (nrepl/message *session* msg)))
