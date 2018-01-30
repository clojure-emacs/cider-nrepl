(ns cider-nrepl.main
  (:require
   [clojure.java.io :as io]
   [clojure.tools.nrepl.server :as nrepl.server]))

(defn- require-and-resolve
  [thing]
  (require (symbol (namespace thing)))
  (resolve thing))

(defn- handle-seq-var
  [var]
  (let [x @var]
    (if (sequential? x)
      (map require-and-resolve x)
      [var])))

(defn- ->mw-list
  [middleware-var-strs]
  (mapcat (comp handle-seq-var require-and-resolve symbol)
          middleware-var-strs))

(defn- start-nrepl
  [handler]
  (let [nrepl-port (io/file ".nrepl-port")
        {:keys [server-socket port] :as server}
        (nrepl.server/start-server :handler handler)
        bind (-> server-socket (.getInetAddress) (.getHostName))]
    (doto nrepl-port
      (spit port)
      (.deleteOnExit))
    (println (format "nREPL server started on port %d on host %s - nrepl://%s:%d" port bind bind port))
    server))

(defn init
  [middleware-syms]
  (start-nrepl (apply nrepl.server/default-handler (->mw-list middleware-syms)))
  ;; Return nil so the value doesn't print
  nil)
