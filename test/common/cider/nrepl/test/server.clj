(ns cider.nrepl.test.server
  (:require
    [clojure.tools.nrepl.server :as nrepl.server]))

;; This exists to work around https://dev.clojure.org/jira/browse/NREPL-87
(defn start-server
  [& {:as args}]
  (try
    (apply nrepl.server/start-server (apply concat args))
    (catch java.net.SocketException e
      (try
        (apply nrepl.server/start-server (apply
                                           concat
                                           (assoc args :bind "127.0.0.1")))

        (catch java.net.SocketException _
          (throw e))))))
