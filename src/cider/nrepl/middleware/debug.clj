(ns cider.nrepl.middleware.debug
  "Expression-based debugger for clojure code"
  {:author "Artur Malabarba"}
  (:require [cider.nrepl.middleware.inspect :refer [swap-inspector!]]
            [cider.nrepl.middleware.pprint :as pprint]
            [cider.nrepl.middleware.stacktrace :as stacktrace]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.inspect :as inspect]
            [cider.nrepl.middleware.util.instrument :as ins]
            [cider.nrepl.middleware.util.meta :as m]
            [cider.nrepl.middleware.util.misc :as misc]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.walk :as walk])
  (:import [clojure.lang Compiler$LocalBinding]))

(defn random-uuid-str []
  (letfn [(hex [] (format "%x" (rand-int 15)))
          (nhex [n] (apply str (repeatedly n hex)))]
    (let [rhex (format "%x" (bit-or 0x8 (bit-and 0x3 (rand-int 14))))]
      (str (nhex 8) "-" (nhex 4) "-4" (nhex 3)
           "-" rhex (nhex 3) "-" (nhex 12)))))

;;;; # The Debugger
;;; The debugger is divided into two parts, intrument.clj and
;;; debug.clj.
;;;
;;; - instrument.clj (which see), found in the util/ subdir, is
;;;   responsible for navigating a code data structure and
;;;   instrumenting all sexps of interest.
;;;
;;; - debug.clj is the debugger per se. It doesn't actually "look" at
;;;   the code it's being run on. It simply implements the breakpoint
;;;   logic as well as a number of functions to interact with the user
;;;   according to breakpoints placed by instrument.clj.
;;;
;;;   After the repl is started, before the debugger can be used, a
;;;   message must be sent by the client with the "init-debugger" op
;;;   (only one of these is necessary througout a session).
;;;   Afterwards, code can be instrumented by calling
;;;   `instrument-and-eval` on it (through the regular "eval" op).
;;;
;;;   Finally, when a breakpoint is reached due to running
;;;   instrumented code, an `need-debug-input` message is sent to the
;;;   client in response to the message used during initialization.
;;;   Execution of the code will halt until this message is replied.
;;;   It may specify a :prompt, it will specify an :input-type and a
;;;   :key, and it expects an :input key in the reply. The :key must
;;;   be contained in the reply, and :input-type may be:
;;;   - a vector of keywords, in which case one must be returned (but
;;;     note that the repl may convert these to non-keyword strings);
;;;   - the keyword :expression, in which case a single sexp must be
;;;     returned (as a string).

;;;; ## Internal breakpoint logic
;;; Variables and functions used for navigating between breakpoints.
(def ^:dynamic *skip-breaks*
  "Map used to determine whether to skip a breakpoint.
  Don't set or examine this directly, it is bound in the session
  binding map, use `skip-breaks!` and `skip-breaks?` instead.
  Its value is discarded at the end each eval session."
  nil)

(def ^:dynamic *extras*
  "Bound by `with-debug-bindings` to a modified version of the original `*msg*`."
  {})

(def ^:dynamic *locals*
  "Bound by `with-debug-bindings` to a map from local symbols to values."
  {})

(def ^:dynamic *pprint-fn*
  "Bound by `with-debug-bindings` to the pretty-printing function determined by
  the `wrap-pprint-fn` middleware."
  nil)

(defn coord<
  "Return true if coordinate x comes before y.
  Here, \"comes before\" means that a sexp at coord x is evaluated
  before a sexp at coord y (assuming a trivial code-flow)."
  [x y]
  (if (seq x)
    (if (seq y)
      (let [fa (first x)
            fb (first y)]
        (if (= fa fb)
          (recur (rest x) (rest y))
          (< fa fb)))
      ;; If coord `x` goes deeper than `y`, then is `x < y`.
      true)
    false))

(defn- seq= [a b]
  ;; To deal with, eg: (= () nil) => true
  (= (seq a) (seq b)))

(defn skip-breaks?
  "True if the breakpoint at coordinates should be skipped.

  The `*skip-breaks*` map stores a `mode`, `coordinates`, the `code` that it
  applies to, and a `force?` flag. Behaviour depends on the `mode`:
   - :all - return true, skipping all breaks
   - :trace - return false, skip nothing
   - :deeper - return true if the given coordinates are deeper than the
               coordinates stored in `*skip-breaks*`, in the same code
   - :before - return true if the given coordinates represent a place before
               the coordinates in `*skip-breaks*`, in the same code

  For :deeper and :before, if we are not in the same code (i.e. we have stepped
  into another instrumented function), then return the value of `force?`."
  [coordinates]
  (if (seq coordinates)
    (when-let [{mode :mode skip-coords :coor
                code-to-skip :code force? :force?} @*skip-breaks*]
      (let [same-defn? (identical? code-to-skip (:code *extras*))]
        (case mode
          ;; From :continue, skip everything.
          :all    true
          ;; From :trace, never skip.
          :trace  false
          ;; From :out, skip some breaks.
          :deeper (if same-defn?
                    (let [parent (take (count skip-coords) coordinates)]
                      (and (seq= skip-coords parent)
                           (> (count coordinates) (count parent))))
                    force?)
          ;; From :here, skip some breaks.
          :before (if same-defn?
                    (coord< coordinates skip-coords)
                    force?))))
    ;; We don't breakpoint top-level sexps, because their return value
    ;; is already displayed anyway.
    true))

(defn skip-breaks!
  "Set the value of *skip-breaks* for the top-level breakpoint.
  Additional arguments depend on mode, and should be:
   - empty for :all or :trace
   - coordinates, code, and force for :deeper or :before
  See `skip-breaks?`."
  ([mode]
   (skip-breaks! mode nil nil nil))
  ([mode coor code force?]
   (reset! *skip-breaks*
           (case mode
             (nil false)       nil
             (:all :trace)     {:mode mode}
             (:deeper :before) {:mode mode :coor coor :code code :force? force?}))))

(defn- abort!
  "Stop current eval thread.
  This does not quit the repl, it only interrupts an eval operation."
  []
  (if (map? *msg*)
    (do
      (transport/send (:transport *msg*) (response-for *msg* :value 'QUIT))
      (.stop (:thread (meta (:session *msg*)))))
    ;; We can't really abort if there's no *msg*, so we do our best
    ;; impression of that. This is only used in some panic situations,
    ;; the user won't be offered the :quit option if there's no *msg*.
    (skip-breaks! :all)))

;;; Politely borrowed from clj-debugger.
(defn- sanitize-env
  "Turn a macro's &env into a map usable for binding."
  [env]
  (into {} (for [[sym bind] env
                 :when (instance? Compiler$LocalBinding bind)]
             [`(quote ~sym) (.sym bind)])))

;;;; ## Getting user input
;;; `wrap-debug` receives an initial message from the client, stores
;;; it in `debugger-message`, and `breakpoint` answers it when asking
;;; for input.
(def debugger-message
  "The message being used to communicate with the client.
  Stored by the \"init-debugger\" op, and used by `read-debug` to ask
  for debug input through the :need-debug-input status."
  (atom nil))

(def promises
  "Map atom holding all unprocessed debug inputs.
  This is where the \"debug\" op stores replies received for debug
  input requests. `read-debug` will halt until it finds its input in
  this map (identified by a key), and will `dissoc` it afterwards."
  (atom {}))

(def print-length (atom nil))
(def print-level (atom nil))

(defn pr-short
  "Like `pr-str` but limited in length and depth."
  [x]
  (binding [*print-length* @print-length
            *print-level*  @print-level]
    (pr-str x)))

(defn- locals-for-message
  "Prepare a map of local variables for sending through the repl.
  This involves removing any keys whose name looks like it was
  autogenerated by a macro, and turning keys and values to strings."
  [m]
  (map (partial map pr-short)
       (remove (fn [[k]] (re-find  #"_" (name k))) m)))

(defn debugger-send
  "Send a response through debugger-message."
  [& r]
  (try
    (transport/send (:transport @debugger-message)
                    (apply response-for @debugger-message r))
    (catch java.net.SocketException _
      (reset! debugger-message nil))))

(defn- read-debug
  "Like `read`, but reply is sent through `debugger-message`.
  type is sent in the message as :input-type."
  [extras type prompt]
  (let [key (random-uuid-str)
        input (promise)]
    (swap! promises assoc key input)
    (debugger-send (assoc extras
                          :status :need-debug-input
                          :locals (locals-for-message *locals*)
                          :key key
                          :prompt prompt
                          :input-type type))
    @input))

(defn- eval-with-locals
  "`eval` form wrapped in a let of the *locals* map.
  If an exception is thrown, it is caught and sent to the client, and
  this function returns nil."
  [form]
  (let [ns (ns-name *ns*)]
    (try
      (eval `(let ~(vec (mapcat #(list % `(*locals* '~%))
                                (keys *locals*)))
               ~form))
      (catch Exception e
        ;; Borrowed from `interruptible-eval/evaluate`.
        (let [root-ex (#'clojure.main/root-cause e)]
          (when-not (instance? ThreadDeath root-ex)
            (debugger-send
             {:status :eval-error
              :causes [(let [causes (stacktrace/analyze-causes e *pprint-fn*)]
                         (when (coll? causes) (last causes)))]})))
        nil)
      (finally
        (try
          (in-ns ns)
          ;; See https://github.com/clojure-emacs/cider/issues/1604
          (catch IllegalStateException _))))))

(defn- read-debug-eval-expression
  "Read and eval an expression from the client.
  extras is a map to be added to the message, and prompt is added into
  the :prompt key."
  ([prompt extras] (read-debug-eval-expression prompt extras nil))
  ([prompt extras code]
   (eval-with-locals (or code (read-debug extras :expression prompt)))))

(declare read-debug-command)

(defn inspect-then-read-command
  "Inspect `inspect-value` and send it as part of a new `read-debug-command`.
  This `read-debug-command` is passed `value` and the `extras` map
  with the result of the inspection `assoc`ed in."
  [value extras page-size inspect-value]
  (let [i (binding [*print-length* nil
                    *print-level* nil]
            (->> #(inspect/start (assoc % :page-size page-size) inspect-value)
                 (swap-inspector! @debugger-message)
                 :rendered pr-str))]
    (read-debug-command value (assoc extras :inspect i))))

(defn stack-then-read-command
  "Create a dummy exception, send its stack, and read a debugging command.
  Stack is sent via `debugger-message`, under the :causes entry
  with the :status entry set to :stack-trace.
  value and extras are passed unmodified to `read-debug-command`."
  [value extras]
  (debugger-send
   {:status :stack
    :causes [{:class "StackTrace"
              :message "Harmless user-requested stacktrace"
              :stacktrace (-> (Exception. "Dummy")
                              (stacktrace/analyze-causes *pprint-fn*)
                              last :stacktrace)}]})
  (read-debug-command value extras))

(defn read-debug-command
  "Read and take action on a debugging command.
  Ask for one of the following debug commands using `read-debug`:

    next: Return value.
    continue: Skip breakpoints for the remainder of this eval session.
    out: Skip breakpoints in the current sexp.
    inspect: Evaluate an expression and inspect it.
    locals: Inspect local variables.
    inject: Evaluate an expression and return it.
    eval: Evaluate an expression, display result, and prompt again.
    stacktrace: Print the current stacktrace, and prompt again.
    quit: Abort current eval session.

  Response received can be any one of these values. It can also be a
  map whose :response entry is one of these values, which can thus be
  used to provide aditional parameters. For instance, if this map has
  a :code entry, its value is used for operations such as :eval, which
  would otherwise interactively prompt for an expression."
  [value extras]
  (let [commands (cond->> [:next :continue :out :here :inspect :locals :inject :eval :stacktrace :trace :quit]
                   (not (map? *msg*)) (remove #{:quit})
                   (cljs/grab-cljs-env *msg*) identity)
        response-raw (read-debug extras commands nil)

        {:keys [code coord response page-size force?] :or {page-size 32}}
        (if (map? response-raw) response-raw
            {:response response-raw})
        extras (dissoc extras :inspect)]
    (case response
      :next     value
      :continue (do (skip-breaks! :all) value)
      :out      (do (skip-breaks! :deeper (butlast (:coor extras)) (:code extras) force?)
                    value)
      :here     (do (skip-breaks! :before coord (:code extras) force?)
                    value)
      :locals   (inspect-then-read-command value extras page-size *locals*)
      :inspect  (->> (read-debug-eval-expression "Inspect value: " extras code)
                     (inspect-then-read-command value extras page-size))
      :inject   (read-debug-eval-expression "Expression to inject: " extras code)
      :eval     (let [return (read-debug-eval-expression "Expression to evaluate: " extras code)]
                  (read-debug-command value (assoc extras :debug-value (pr-short return))))
      :stacktrace (stack-then-read-command value extras)
      :trace    (do (skip-breaks! :trace) value)
      :quit     (abort!))))

(defn print-step-indented [depth form value]
  (print (apply str (repeat (dec depth) "| ")))
  (binding [*print-length* 4
            *print-level*  2]
    (pr form))
  (println "=>" (pr-short value)))

;;; ## High-level functions
(defmacro with-debug-bindings
  "Run body with a debug environment.
  This means `debugger-message` is guaranteed to be set, and
  `*pprint-fn*`, `*locals*`, and `*skip-breaks*` are guaranteed to be
  bound to reasonable values (on this thread). An exception will be
  thrown if `debugger-message` wasn't set."
  {:style/indent 1}
  [extras & body]
  `(binding [*skip-breaks* (or *skip-breaks* (atom nil))
             *locals*      ~(sanitize-env &env)
             ;; This *msg* is evaluated at compile-time, so it's the
             ;; message that instrumented the function, not the
             ;; message that led to its evaluation.
             *extras*      ~(let [{:keys [code id file line column]} *msg*]
                              (-> {:code code, :original-id id, :file file,
                                   :line line, :column column}
                                  (merge extras)
                                  ;; There's an nrepl bug where the column starts counting at 1 if it's
                                  ;; after the first line. Since this is a top-level sexp, a (= col 1)
                                  ;; is much more likely to be wrong than right.
                                  (update :column #(if (= % 1) 0 %))))
             *pprint-fn*   (:pprint-fn *msg*)]
     (when (not (seq @debugger-message))
       (throw (Exception. "Debugger not initialized")))
     ~@body))

(defmacro breakpoint-implementation [form {:keys [coor]} original-form]
  `(let [val# ~form]
     (cond
       (skip-breaks? ~coor) val#
       ;; The length of `coor` is a good indicator of current code
       ;; depth.
       (= (:mode @*skip-breaks*) :trace)
       (do (print-step-indented ~(count coor) '~original-form val#)
           val#)
       ;; Nothing special. Here's the actual breakpoint logic.
       :else (->> (pr-short val#)
                  (assoc *extras* :debug-value)
                  (read-debug-command val#)))))

(defmacro breakpoint
  "Send the result of form and its coordinates to the client.
  Sends a response to the message stored in debugger-message."
  [form {:keys [coor] :as extras} original-form]
  (let [has-condition? (contains? (meta form) :break/when)
        condition      (:break/when (meta form))
        parent-coor    (vec (butlast coor))]
    (if has-condition?
      `(with-debug-bindings ~extras
         ;; If there is a condition and it is falsy, we need to skip
         ;; the current level (:deeper than parent coor), but only
         ;; once. Next time, we need to test the condition again.
         (binding [*skip-breaks* (if ~condition
                                   *skip-breaks*
                                   (atom {:mode   :deeper
                                          :coor   ~parent-coor
                                          :code   ~(:code *msg*)
                                          :force? false}))]
           (breakpoint-implementation ~form ~extras ~original-form)))
      `(with-debug-bindings ~extras
         (breakpoint-implementation ~form ~extras ~original-form)))))


(def irrelevant-return-value-forms
  "Set of special-forms whose return value we don't care about.
  When instrumenting, these will not be wrapped in a breakpoint."
  '#{def fn* deftype* reify* monitor-enter monitor-exit})

(defmacro breakpoint-if-interesting
  "Wrap form in a breakpoint if it looks interesting.
  Uninteresting forms are symbols that resolve to `clojure.core`
  (taking locals into account), and sexps whose head is present in
  `irrelevant-return-value-forms`."
  [form & rest]
  (if (or (and (symbol? form)
               (not (contains? &env form))
               (try
                 (-> (resolve form) meta :ns
                     ns-name (= 'clojure.core))
                 (catch Exception _ nil)))
          (and (seq? form)
               (irrelevant-return-value-forms (first form))))
    form
    `(breakpoint ~form ~@rest)))

;;; Data readers
;; Set in `src/data_readers.clj`.
(defn breakpoint-reader
  "#break reader. Mark `form` for breakpointing."
  [form]
  (ins/tag-form form #'breakpoint-if-interesting))

(defn debug-reader
  "#dbg reader. Mark all forms in `form` for breakpointing.
  `form` itself is also marked."
  [form]
  (ins/tag-form-recursively form #'breakpoint-if-interesting))

(defn instrument-and-eval [form]
  (eval (ins/instrument-tagged-code form)))

;;; Middleware setup
(defn- maybe-debug
  "Return msg, prepared for debugging if code contains debugging macros."
  [{:keys [code session ns] :as msg}]
  (when (instance? clojure.lang.Atom session)
    (swap! session assoc #'*skip-breaks* (atom nil)))
  ;; The best way of checking if there's a #break reader-macro in
  ;; `code` is by reading it, in which case it toggles `has-debug?`.
  (let [has-debug? (atom false)
        fake-reader (fn [x] (reset! has-debug? true) nil)]
    (binding [*data-readers* (->> (repeat fake-reader)
                                  (interleave '[dbg break light])
                                  (apply assoc *data-readers*))]
      (try
        (read-string code)
        (catch Exception e)))
    (if @has-debug?
      ;; Technically, `instrument-and-eval` acts like a regular eval
      ;; if there are no debugging macros. But we still only use it
      ;; when we know it's necessary.
      (assoc msg :eval "cider.nrepl.middleware.debug/instrument-and-eval")
      ;; If there was no reader macro, fallback on regular eval.
      msg)))

(defn- initialize
  "Initialize the channel used for debug-input requests."
  [{:keys [print-length print-level] :as msg}]
  (when (map? @debugger-message)
    (debugger-send :status :done))
  ;; The above is just bureaucracy. The below is important.
  (reset! @#'print-length print-length)
  (reset! @#'print-level print-level)
  (reset! debugger-message msg))

(defn- instrumented-defs-reply
  "Reply to `msg` with an alist of instrumented defs on the \"list\" entry."
  [msg]
  (->> (all-ns)
       (map #(cons (ns-name %) (ins/list-instrumented-defs %)))
       (filter second)
       misc/transform-value
       (response-for msg :status :done :list)
       (transport/send (:transport msg))))

(defn wrap-debug [h]
  (fn [{:keys [op input] :as msg}]
    (case op
      "eval" (h (maybe-debug msg))
      "debug-instrumented-defs" (instrumented-defs-reply msg)
      "debug-input" (when-let [pro (@promises (:key msg))]
                      (swap! promises dissoc  (:key msg))
                      (try (deliver pro (read-string input))
                           (catch Exception e
                             (when-not (realized? pro)
                               (deliver pro :quit))
                             (throw e))))
      "init-debugger" (initialize msg)
      ;; else
      (h msg))))

(set-descriptor!
 #'wrap-debug
 (cljs/requires-piggieback
  {:expects #{"eval"}
   :requires #{#'pprint/wrap-pprint-fn}
   :handles
   {"debug-input"
    {:doc "Read client input on debug action."
     :requires {"input" "The user's reply to the input request."}
     :returns {"status" "done"}}
    "init-debugger"
    {:doc "Initialize the debugger so that `breakpoint` works correctly.
This usually does not respond immediately. It sends a response when a
breakpoint is reached or when the message is discarded."
     :requires {"id" "A message id that will be responded to when a breakpoint is reached."}}
    "debug-instrumented-defs"
    {:doc "Return an alist of definitions currently thought to be instrumented on each namespace.
Due to Clojure's versatility, this could include false postives, but
there will not be false negatives. Instrumentations inside protocols
are not listed."
     :returns {"status" "done"
               "list" "The alist of (NAMESPACE . VARS) that are thought to be instrumented."}}
    "debug-middleware"
    {:doc "Debug a code form or fall back on regular eval."
     :requires {"id" "A message id that will be responded to when a breakpoint is reached."
                "code" "Code to debug, there must be a #dbg or a #break reader macro in it, or nothing will happen."
                "file" "File where the code is located."
                "ns" "Passed to \"eval\"."
                "point" "Position in the file where the provided code begins."}
     :returns {"status" "\"done\" if the message will no longer be used, or \"need-debug-input\" during debugging sessions"}}}}))
