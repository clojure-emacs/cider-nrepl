(ns cider.nrepl.middleware.out
  "Change *out*, *err*, System/out and System/err to print on sessions
  in addition to process out.

  Automatically changes the root binding of all output channels to
  print to any active sessions. An active session is one that has sent
  at least one \"eval\" op.

  We use an eval message, instead of the clone op, because there's no
  guarantee that the channel that sent the clone message will properly
  handle output replies."
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [clojure.string :as s]
            [clojure.tools.nrepl.middleware.interruptible-eval :as ie])
  (:import [java.io PrintWriter Writer PrintStream OutputStream]))

(declare unsubscribe-session)

(def stdout-map
  {:out *out*
   :err *err*})

(defmacro with-out-binding
  "Run body with v bound to the output stream of each msg in msg-seq.
  type is either :out or :err."
  [[v msg-seq type] & body]
  `(doseq [{:keys [~'session] :as ~'msg} ~msg-seq]
     (let [~(with-meta v {:tag Writer}) (get @~'session
                                             (case ~type
                                               :out #'*out*
                                               :err #'*err*))]
       (try (binding [ie/*msg* ~'msg]
              ~@body)
            ;; If a channel is faulty, dissoc it.
            (catch Exception ~'e
              (unsubscribe-session ~'session))))))

(defn forking-printer
  "Returns a PrintWriter suitable for binding as *out* or *err*. All
  operations are forwarded to all output bindings in the sessions of
  messages.
  type is either :out or :err."
  [messages type]
  (PrintWriter. (proxy [Writer] []
                  (close [] (.flush ^Writer this))
                  (write
                    ([x]
                     (.write (stdout-map type) x)
                     (with-out-binding [printer messages type]
                       (.write printer x)))
                    ([x ^Integer off ^Integer len]
                     (.write (stdout-map type) x off len)
                     (with-out-binding [printer messages type]
                       (.write printer x off len))))
                  (flush []
                    (.flush (stdout-map type))
                    (with-out-binding [printer messages type]
                      (.flush printer))))
                true))

(defn print-stream
  "Returns a PrintStream suitable for binding as java.lang.System/out
  or java.lang.System/err. All operations are forwarded to all output
  bindings in the sessions of messages.
  type is either :out or :err."
  [type]
  (let [printer (case type
                  :out '*out*
                  :err '*err*)]
    (PrintStream. (proxy [OutputStream] []
                    (close [] (.flush ^OutputStream this))
                    (write
                      ([int-or-bytes]
                       (.write @(resolve printer)
                               (if (instance? Integer int-or-bytes)
                                 int-or-bytes
                                 (String. int-or-bytes))))
                      ([bytes ^Integer off ^Integer len]
                       (let [byte-range (byte-array
                                         (take len (drop off bytes)))]
                         (.write @(resolve printer) (String. byte-range)))))
                    (flush []
                      (.flush @(resolve printer))))
                  true)))

;;; Known eval sessions
(def tracked-sessions-map
  "Map from session ids to eval `*msg*`s.
  Only the most recent message from each session is stored."
  (atom {}))

(defn tracked-sessions-map-watch [_ _ _ new-state]
  (let [out-writer (forking-printer (vals new-state) :out)
        err-writer (forking-printer (vals new-state) :err)]
    (alter-var-root #'*out* (constantly out-writer))
    (alter-var-root #'*err* (constantly err-writer))
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

(defn handle-out [handler msg]
  (with-safe-transport handler msg
    "out-subscribe" subscribe-session
    "out-unsubscribe" unsubscribe-session))
