(ns cider.nrepl.middleware.out
  "Change *out* to print on sessions in addition to process out.

  Automatically changes the root binding of *out* to print to any
  active sessions. An active session is one that has sent at least one
  \"eval\" op.

  We use an eval message, instead of the clone op, because there's no
  guarantee that the channel that sent the clone message will properly
  handle output replies."
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [clojure.string :as s]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :as ie]
            [clojure.tools.nrepl.middleware.session :as session])
  (:import [java.io PrintWriter Writer PrintStream OutputStream]))

;;; OutStream
(defonce original-out *out*)
(defonce original-err *err*)

(declare unsubscribe-session)

(defmacro with-out-binding
  "Run body with v bound to the output stream of each msg in msg-seq.
  Also run body with v bound to `original-out`.
  type is either :out or :err."
  [[v msg-seq type] & body]
  `(do (let [~(with-meta v {:tag Writer}) (case ~type
                                            :out original-out
                                            :err original-err)]
         ~@body)
       (doseq [{:keys [~'session] :as ~'msg} ~msg-seq]
         (let [~(with-meta v {:tag Writer}) (get @~'session
                                                 (case ~type
                                                   :out #'*out*
                                                   :err #'*err*))]
           (try (binding [ie/*msg* ~'msg]
                  ~@body)
                ;; If a channel is faulty, dissoc it.
                (catch Exception ~'e
                  (unsubscribe-session ~'session)))))))

(defn forking-printer
  "Returns a PrintWriter suitable for binding as *out* or *err*. All
  operations are forwarded to all output bindings in the sessions of
  messages in addition to the server's usual PrintWriter (saved in
  `original-out` or `original-err`).
  type is either :out or :err."
  [messages type]
  (PrintWriter. (proxy [Writer] []
                  (close [] (.flush ^Writer this))
                  (write
                    ([x]
                     (with-out-binding [printer messages type]
                       (.write printer x)))
                    ([x ^Integer off ^Integer len]
                     (with-out-binding [printer messages type]
                       (.write printer x off len))))
                  (flush []
                    (with-out-binding [printer messages type]
                      (.flush printer))))
                true))

(defn print-stream
  "Returns a PrintStream suitable for binding as java.lang.System/out
  or java.lang.System/err. All operations are forwarded to all output
  bindings in the sessions of messages in addition to the server's
  usual PrintWriter (saved in `original-out` or `original-err`).
  type is either :out or :err."
  [type]
  (let [printer (case type
                  :out '*out*
                  :err '*err*)]
    (PrintStream. (proxy [OutputStream] []
                    (close [] (.flush ^OutputStream this))
                    (write
                      ([b]
                       (.write @(resolve printer) (String. b)))
                      ([b ^Integer off ^Integer len]
                       (.write @(resolve printer) (String. b) off len)))
                    (flush []
                      (.flush @(resolve printer))))
                  true)))

;;; Known eval sessions
(def tracked-sessions-map
  "Map from session ids to eval `*msg*`s.
  Only the most recent message from each session is stored."
  (atom {}))

(defn tracked-sessions-map-watch [_ _ _ new-state]
  (let [ow (forking-printer (vals new-state) :out)
        ew (forking-printer (vals new-state) :err)]
    (alter-var-root #'*out* (constantly ow))
    (alter-var-root #'*err* (constantly ew))
    (System/setOut (print-stream :out))
    (System/setErr (print-stream :err))))

(add-watch tracked-sessions-map :update-out tracked-sessions-map-watch)

(defn subscribe-session
  "Add msg to `tracked-sessions-map`."
  [{:keys [session] :as msg}]
  (when-let [session (:id (meta session))]
    (swap! tracked-sessions-map assoc session
           (select-keys msg [:transport :session :id]))
    {:out-subscribe session}))

(defn unsubscribe-session
  "Remove session from `tracked-sessions-map`."
  [session]
  (let [removed (if-let [m (meta session)] (:id m) session)]
    (swap! tracked-sessions-map dissoc removed)
    {:out-unsubscribe removed}))

(defn wrap-out [handler]
  (with-safe-transport handler
    "out-subscribe" subscribe-session
    "out-unsubscribe" unsubscribe-session))

(set-descriptor!
 #'wrap-out
 (cljs/expects-piggieback
  {:requires #{#'session/session}
   :expects #{"eval"}
   :handles
   {"out-subscribe"
    {:doc "Change #'*out* so that it also prints to active sessions, even outside an eval scope."}
    "out-unsubscribe"
    {:doc "Change #'*out* so that it no longer prints to active sessions outside an eval scope."}}}))
