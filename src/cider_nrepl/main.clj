(ns cider-nrepl.main
  (:require
   [clojure.java.io :as io]
   [clojure.tools.nrepl.server :as nrepl.server]))

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

(defn start-nrepl
  [opts]
  (let [{:keys [handler middleware bind port]} opts

        handler (cond-> (or handler nrepl.server/default-handler)
                  middleware (apply (->mw-list middleware)))

        {:keys [server-socket port] :as server}
        (nrepl.server/start-server :handler handler
                                   :bind bind
                                   :port port)

        bind
        (-> server-socket (.getInetAddress) (.getHostName))]
    (doto (io/file ".nrepl-port")
      (spit port)
      (.deleteOnExit))
    (println (format "nREPL server started on port %d on host %s - nrepl://%s:%d" port bind bind port))
    server))

(defn init
  ([]
   (init nil))
  ([opts]
   (start-nrepl opts)
   ;; Return nil so the value doesn't print
   nil))
