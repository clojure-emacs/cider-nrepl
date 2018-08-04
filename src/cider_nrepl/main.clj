(ns cider-nrepl.main
  (:require
   [clojure.java.io :as io]))

(if (find-ns 'clojure.tools.nrepl)
  (require
   '[clojure.tools.nrepl.server :as nrepl.server])
  (require
   '[nrepl.server]))

(defn- require-and-resolve
  [thing]
  (require (symbol (namespace thing)))
  (resolve thing))

(def resolve-mw-xf
  (comp (map require-and-resolve)
        (keep identity)))

(defn- handle-seq-var
  [var]
  (let [x @var]
    (if (sequential? x)
      (into [] resolve-mw-xf x)
      [var])))

(def mw-xf
  (comp (map symbol)
        resolve-mw-xf
        (mapcat handle-seq-var)))

(defn- ->mw-list
  [middleware-var-strs]
  (into [] mw-xf middleware-var-strs))

(defn- build-handler
  [middleware]
  (apply nrepl.server/default-handler (->mw-list middleware)))

(defn start-nrepl
  "Starts a socket-based nREPL server. Accepts a map with the following keys:

   * :port — defaults to 0, which autoselects an open port

   * :bind — bind address, by default \"::\" (falling back to \"localhost\" if
     \"::\" isn't resolved by the underlying network stack)

   * :handler — the nREPL message handler to use for each incoming connection;
     defaults to the result of `(nrepl.server/default-handler)`

   * :middleware - a sequence of vars or string which can be resolved to vars,
     representing middleware you wish to mix in to the nREPL handler. Vars can
     resolve to a sequence of vars, in which case they'll be flattened into the
     list of middleware."
  [{:keys [handler middleware bind port] :as opts}]
  (let [handler
        (if handler
          (handler)
          (build-handler middleware))

        {:keys [server-socket port] :as server}
        (nrepl.server/start-server :handler handler
                                   :bind (or bind "localhost")
                                   :port (or port 0))

        bind
        (-> server-socket (.getInetAddress) (.getHostName))]
    (doto (io/file ".nrepl-port")
      (spit port)
      (.deleteOnExit))
    (println (format "nREPL server started on port %d on host %s - nrepl://%s:%d" port bind bind port))
    server))

(defn init
  ([]
   (start-nrepl {}))
  ([middleware]
   (start-nrepl {:middleware middleware})
   ;; Return nil so the value doesn't print
   nil))
