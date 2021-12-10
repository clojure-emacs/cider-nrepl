(ns cider.nrepl.middleware.out
  "Change *out*, *err*, System/out and System/err to print on sessions
  in addition to process out.

  Automatically changes the root binding of all output channels to
  print to any active sessions. An active session is one that has sent
  at least one \"eval\" op.

  We use an eval message, instead of the clone op, because there's no
  guarantee that the channel that sent the clone message will properly
  handle output replies."
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]])
  (:import
   [java.io PrintWriter Writer PrintStream OutputStream]
   [java.util.concurrent
    Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(declare unsubscribe-session)

(defonce
  ^{:doc "Store the values of the original output streams so we can refer to them.
Please do not inline; they must not be recomputed at runtime."}
  original-output
  {:out *out*
   :err *err*})

(defmacro with-out-binding
  "Run body with v bound to the output stream of each msg in msg-seq.
  type is either :out or :err."
  [[v msg-seq type] & body]
  `(doseq [{:keys [~'session] :as ~'msg} ~msg-seq]
     (let [~(with-meta v {:tag 'java.io.Writer}) (get @~'session
                                                      (case ~type
                                                        :out #'*out*
                                                        :err #'*err*))]
       (try
         ~@body
         ;; If a channel is faulty, dissoc it.
         (catch Exception ~'e
           (unsubscribe-session ~'session))))))

(defn- dispatch-string
  ([messages type ^String x]
   (.write ^Writer (original-output type) x)
   (with-out-binding [printer messages type]
     (.write printer x)))
  ([messages type  ^String x ^Integer off ^Integer len]
   (.write ^Writer (original-output type) x off len)
   (with-out-binding [printer messages type]
     (.write printer x off len))))

(defn- dispatch-int
  ([messages type ^Integer x]
   (.write ^Writer (original-output type) x)
   (with-out-binding [printer messages type]
     (.write printer x))))

(defn- dispatch-chars
  ([messages type ^{:tag "[C"} x]
   (.write ^Writer (original-output type) x)
   (with-out-binding [printer messages type]
     (.write printer x)))
  ([messages type ^{:tag "[C"} x ^Integer off ^Integer len]
   (.write ^Writer (original-output type) x off len)
   (with-out-binding [printer messages type]
     (.write printer x off len))))

(defn forking-printer
  "Returns a PrintWriter suitable for binding as *out* or *err*. All
  operations are forwarded to all output bindings in the sessions of
  `messages`, in addition to the server's usual PrintWriter (saved in
  `original-output`).

  `type` is either :out or :err."
  [messages type]
  (PrintWriter. (proxy [Writer] []
                  (close [] (.flush ^Writer this))
                  ;; unfortunately we can't type hint the method argument
                  ;; as `int` and `char[]` aren't supported by proxy.
                  (write
                    ([x]
                     (cond
                       (string? x) (dispatch-string messages type x)
                       (integer? x) (dispatch-int messages type x)
                       :else (dispatch-chars messages type x)))
                    ([x ^Integer off ^Integer len]
                     (if (string? x)
                       (dispatch-string messages type x off len)
                       (dispatch-chars messages type x off len))))
                  (flush []
                    (.flush ^Writer (original-output type))
                    (with-out-binding [printer messages type]
                      (.flush printer))))
                true))

(def ^ScheduledExecutorService flush-executor
  (Executors/newScheduledThreadPool
   1
   (proxy [ThreadFactory] []
     (newThread [^Runnable r]
       (doto (Thread. r "cider-nrepl output flusher")
         (.setDaemon true))))))

(defn print-stream
  "Returns a PrintStream suitable for binding as java.lang.System/out or
  java.lang.System/err. All operations are forwarded to all output
  bindings in the sessions of messages, in addition to the server's
  usual PrintWriter (saved in `original-output`).

  `printer` is the printer var, either #'clojure.core/*out* or
  #'clojure.core/*err*."
  [printer]
  (let [delay 100
        print-flusher (fn [] (.flush ^Writer @printer))
        flush-future (.scheduleWithFixedDelay
                      flush-executor
                      print-flusher
                      delay delay TimeUnit/MILLISECONDS)]

    (PrintStream.
     (proxy [OutputStream] []
       (close []
         ;; cancel, allowing any running flush to finish
         ;; (false passed as mayInterruptIfRunning argument)
         (.cancel flush-future false)
         (.flush ^OutputStream this))
       (write
         ([int-or-bytes]
          (if (instance? Integer int-or-bytes)
            (.write ^Writer @printer ^Integer int-or-bytes)
            (.write ^Writer @printer (String. ^"[B" int-or-bytes))))
         ([^"[B" bytes ^Integer off ^Integer len]
          (let [byte-range (byte-array (take len (drop off bytes)))]
            (.write ^Writer @printer (String. byte-range)))))
       (flush []
         (.flush ^Writer @printer))))))

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
    (System/setOut (print-stream #'*out*))
    (System/setErr (print-stream #'*err*))))

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
