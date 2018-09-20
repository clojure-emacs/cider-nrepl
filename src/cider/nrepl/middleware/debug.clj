(ns cider.nrepl.middleware.debug
  "Expression-based debugger for clojure code"
  {:author "Artur Malabarba"}
  (:require [orchard.info :as info]
            [cider.nrepl.middleware.inspect :refer [swap-inspector!]]
            [cider.nrepl.middleware.pprint :as pprint]
            [cider.nrepl.middleware.stacktrace :as stacktrace]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [orchard.inspect :as inspect]
            [cider.nrepl.middleware.util.instrument :as ins]
            [orchard.misc :as misc]
            [orchard.meta :as m]
            [clojure.java.io :as io])
  (:import [clojure.lang Compiler$LocalBinding]))

;; Compatibility with the legacy tools.nrepl and the new nREPL 0.4.x.
;; The assumption is that if someone is using old lein repl or boot repl
;; they'll end up using the tools.nrepl, otherwise the modern one.
(if (find-ns 'clojure.tools.nrepl)
  (require
   '[clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
   '[clojure.tools.nrepl.middleware.session :as session]
   '[clojure.tools.nrepl.misc :refer [response-for]]
   '[clojure.tools.nrepl.transport :as transport])
  (require
   '[nrepl.middleware.interruptible-eval :refer [*msg*]]
   '[nrepl.middleware.session :as session]
   '[nrepl.misc :refer [response-for]]
   '[nrepl.transport :as transport]))

;;;; # The Debugger
;;;
;;; The debugger is divided into two parts, instrument.clj and
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
;;;
;;; Variables and functions used for navigating between breakpoints.
(def ^:dynamic *skip-breaks*
  "Map used to determine whether to skip a breakpoint.
  Don't set or examine this directly, it is bound in the session
  binding map, use `skip-breaks!` and `skip-breaks?` instead.
  Its value is discarded at the end each eval session."
  (atom nil))

(defn- random-uuid-str
  "Clojure(Script) UUID generator."
  []
  (letfn [(hex [] (format "%x" (rand-int 15)))
          (nhex [n] (apply str (repeatedly n hex)))]
    (let [rhex (format "%x" (bit-or 0x8 (bit-and 0x3 (rand-int 14))))]
      (str (nhex 8) "-" (nhex 4) "-4" (nhex 3)
           "-" rhex (nhex 3) "-" (nhex 12)))))

(defn- seq=
  "To deal with, eg: (= () nil) => true"
  [a b]
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
  into another instrumented function and code argument doesn't match old code in
  *skip-breaks*), then return the value of `force?`."
  [coordinates code]
  (if (seq coordinates)
    (when-let [{mode :mode skip-coords :coor
                code-to-skip :code force? :force?} @*skip-breaks*]
      (let [same-defn? (identical? code-to-skip code)]
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
                    (ins/coord< coordinates skip-coords)
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

(defn- filter-env
  "Remove internal vars and macro locals with __ in their names."
  [locals]
  (remove (fn [[k]] (re-find  #"__" (name k))) locals))

;;; Politely borrowed from clj-debugger.
(defn sanitize-env
  "Turn a macro's &env into a map usable for binding."
  [env]
  (into {} (for [[sym bind] (filter-env env)
                 :when (instance? Compiler$LocalBinding bind)]
             [`(quote ~sym) (.sym bind)])))

;;;; ## Getting user input
;;;
;;; `wrap-debug` receives an initial message from the client, stores
;;; it in `debugger-message`, and `breakpoint` sends replies on it when
;;; asking for input.

(defonce ^{:doc "Message used to communicate with the client. Stored by the
 \"init-debugger\" op, and used by `read-debug-input` to ask for debug input through
 the :need-debug-input status."}
  debugger-message (atom nil))

(defonce ^{:doc "Map atom holding all unprocessed debug inputs.
This is where the \"debug\" op stores replies received for debug
input requests. `read-debug-input` will halt until it finds its input in
this map (identified by a key), and will `dissoc` it afterwards."}
  promises (atom {}))

(defonce print-length (atom nil))
(defonce print-level (atom nil))
(defonce step-in-to-next? (atom false))

(defn pr-short
  "Like `pr-str` but limited in length and depth."
  [x]
  (binding [*print-length* @print-length
            *print-level*  @print-level]
    (pr-str x)))

(defn- locals-for-message
  "Prepare a map of local variables for sending through the repl."
  [locals]
  (map (partial map pr-short) locals))

(defn debugger-send
  "Send a response through debugger-message."
  [& r]
  (when (not @debugger-message)
    (throw (Exception. "Debugger not initialized!")))
  (try
    (transport/send (:transport @debugger-message)
                    (apply response-for @debugger-message r))
    (catch java.net.SocketException _
      (reset! debugger-message nil))))

(defmacro try-if-let
  "Try binding `sym` to `val` end eval `success-expr` or `error-expr` on error.
  On error send an eval-error message through `debugger-message` channel."
  {:style/indent 1}
  [[sym val] success-expr error-expr]
  `(let [error# (gensym)
         ~sym (try
                ~val
                ;; Borrowed from `interruptible-eval/evaluate`.
                (catch Exception e#
                  (let [root-ex# (#'clojure.main/root-cause e#)]
                    (when-not (instance? ThreadDeath root-ex#)
                      (debugger-send
                       {:status :eval-error
                        :causes [(let [causes# (stacktrace/analyze-causes e# (:pprint-fn *msg*))]
                                   (when (coll? causes#) (last causes#)))]})))
                  error#))]
     (if (= error# ~sym)
       ~error-expr
       ~success-expr)))

(defn- read-debug-input
  "Like `read`, but reply is sent through `debugger-message`."
  [dbg-state input-type prompt]
  (let [key (random-uuid-str)
        input (promise)]
    (swap! promises assoc key input)
    (debugger-send (-> dbg-state
                       (assoc :status :need-debug-input
                              :key key
                              :prompt prompt
                              :input-type input-type)
                       (update :locals locals-for-message)))
    (binding [*ns* (find-ns (symbol (:original-ns dbg-state)))]
      (try (read-string @input)
           (finally (swap! promises dissoc key))))))

(def ^:dynamic *tmp-locals*
  "Many objects don't have reader representation and we cannot simply splice
  them in `eval-with-locals`. Instead, we use this temporary dynamic var to bind
  them during `eval`.")

(defn- eval-with-locals
  "`eval` form wrapped in a let of the locals map.
  If an exception is thrown, it is caught and sent to the client, and this
  function returns nil. `dbg-state` is a metadata map received from `break'."
  [form dbg-state]
  (let [ns (ns-name *ns*)]
    (try
      (binding [*tmp-locals* (:locals dbg-state)
                ;; evaluate in the instrumentation ns
                *ns* (find-ns (symbol (:original-ns dbg-state)))]
        (eval `(let ~(vec (mapcat #(list % `(get *tmp-locals* '~%))
                                  (keys *tmp-locals*)))
                 ~form)))
      (finally
        (try
          ;; Restore original ns in case it was changed by the evaluated
          ;; code. See https://github.com/clojure-emacs/cider/issues/1604.
          (in-ns ns)
          (catch IllegalStateException _))))))

(defn- read-eval-expression
  "Read and eval an expression from the client.
  `dbg-state` is a map received from `break`, and `prompt` is added into
  the :prompt key."
  ([prompt dbg-state] (read-eval-expression prompt dbg-state nil))
  ([prompt dbg-state code]
   (eval-with-locals (or code (read-debug-input dbg-state :expression prompt))
                     dbg-state)))

(declare read-debug-command)

(defn- debug-inspect
  "Inspect `inspect-value`."
  [page-size inspect-value]
  (binding [*print-length* nil
            *print-level* nil]
    (->> #(inspect/start (assoc % :page-size page-size) inspect-value)
         (swap-inspector! @debugger-message)
         :rendered pr-str)))

(defn- debug-stacktrace
  "Create a dummy exception, send its stack."
  []
  (debugger-send
   {:status :stack
    :causes [{:class "StackTrace"
              :message "Harmless user-requested stacktrace"
              :stacktrace (-> (Exception. "Dummy")
                              (stacktrace/analyze-causes (:pprint-fn *msg*))
                              last :stacktrace)}]}))

(def debug-commands
  {"c" :continue
   "e" :eval
   "h" :here
   "i" :in
   "j" :inject
   "l" :locals
   "n" :next
   "o" :out
   "p" :inspect
   "q" :quit
   "s" :stacktrace
   "t" :trace})

(defn read-debug-command
  "Read and take action on a debugging command.
  Ask for one of the following debug commands using `read-debug-input`:

    next: Return value.
    continue: Skip breakpoints for the remainder of this eval session.
    out: Skip breakpoints in the current sexp.
    inspect: Evaluate an expression and inspect it.
    locals: Inspect local variables.
    inject: Evaluate an expression and return it.
    eval: Evaluate an expression, display result, and prompt again.
    stacktrace: Print the current stacktrace, and prompt again.
    quit: Abort current eval session.

  Response received can be any one of these values. It can also be a map
  whose :response entry is one of these values, which can thus be used to
  provide additional parameters. For instance, if this map has a :code entry,
  its value is used for operations such as :eval, which would otherwise
  interactively prompt for an expression."
  [value dbg-state]
  (let [commands     (cond-> debug-commands
                       (not (map? *msg*))         (dissoc "q")
                       (nil? (:locals dbg-state))    (dissoc "e" "j" "l" "p")
                       (cljs/grab-cljs-env *msg*) identity)
        response-raw (read-debug-input dbg-state commands nil)
        dbg-state       (dissoc dbg-state :inspect)

        {:keys [code coord response page-size force?]
         :or {page-size 32}} (if (map? response-raw)
                               response-raw
                               {:response response-raw})]
    (reset! step-in-to-next? false)
    (case response
      :next       value
      :in         (do (reset! step-in-to-next? true)
                      value)
      :continue   (do (skip-breaks! :all)
                      value)
      :out        (do (skip-breaks! :deeper (butlast (:coor dbg-state)) (:code dbg-state) force?)
                      value)
      :here       (do (skip-breaks! :before coord (:code dbg-state) force?)
                      value)
      :stacktrace (do (debug-stacktrace)
                      (recur value dbg-state))
      :trace      (do (skip-breaks! :trace)
                      value)
      :locals     (->> (debug-inspect page-size (:locals dbg-state))
                       (assoc dbg-state :inspect)
                       (recur value))
      :inspect    (try-if-let [val (read-eval-expression "Inspect value: " dbg-state code)]
                    (->> (debug-inspect page-size val)
                         (assoc dbg-state :inspect)
                         (recur value))
                    (recur value dbg-state))
      :inject     (try-if-let [val (read-eval-expression "Expression to inject: " dbg-state code)]
                    val
                    (recur value dbg-state))
      :eval       (try-if-let [val (read-eval-expression "Expression to evaluate: " dbg-state code)]
                    (recur value (assoc dbg-state :debug-value (pr-short val)))
                    (recur value dbg-state))
      :quit       (abort!)
      (do (abort!)
          (throw (ex-info "Invalid input from `read-debug-input`."
                          {:response-raw response-raw}))))))

(defn print-step-indented [depth form value]
  (print (apply str (repeat (dec depth) "| ")))
  (binding [*print-length* 4
            *print-level*  2]
    (pr form))
  (println "=>" (pr-short value)))

(declare debug-reader)

(defn instrument-var-for-step-in
  "Attach an instrumented version of the function in `v` as metadata to `v`,
  leaving the contents of the var uninstrumented."
  [v]
  (when-not (::instrumented (meta v))
    (when-let [{:keys [ns file form] :as var-meta} (m/var-code v)]
      (let [full-path (misc/transform-value (:file (info/file-info file)))]
        (binding [*ns*   ns
                  *file* file
                  *msg*  (-> *msg*
                             (merge var-meta)
                             (assoc :file full-path))]
          (let [iform `(with-initial-debug-bindings
                         ~(ins/instrument-tagged-code (debug-reader form)))]
            ;; (ins/print-form iform true)
            (eval iform)
            (let [instrumented @v]
              (eval form)
              (alter-meta! v assoc ::instrumented instrumented))))))))

(defn safe-to-debug?
  "Some namespaces are not safe to debug, because doing so can cause a stack
  overflow that crashes the nrepl process."
  [ns]
  (not (#{'clojure.core} (ns-name ns))))

(defn step-in?
  "Return true if we can and should step in to the function in the var `v`.
  The \"should\" part is determined by the value in `step-in-to-next?`, which
  gets set to true by the user sending the \"step in\" command."
  [v coor code]
  (when (and @step-in-to-next?
             (not (skip-breaks? coor code))
             (not= :trace (:mode @*skip-breaks*))
             (not (:instrumented (meta v))))
    (try
      (instrument-var-for-step-in v)
      true
      (catch Exception e
        ;; TODO - how do we inform the user that we failed?
        (println (.getMessage e))
        false))))

(defn looks-step-innable?
  "Decide whether a form looks like a call to a function that we could
  instrument and step into."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (let [v (resolve (first form)) ; Note: special forms resolve to nil
          m (meta v)]
      ;; We do not go so far as to actually try to read the code of the function
      ;; at this point, which is at macroexpansion time.
      (and v
           (safe-to-debug? (:ns m))
           (not (:macro m))
           (not (:inline m))))))

;;;  ## Breakpoint logic

(def ^:dynamic *tmp-forms* (atom {}))
(def ^:dynamic *do-locals* true)

(defmacro with-initial-debug-bindings
  "Let-wrap `body` with STATE__ map containing code, file, line, column etc.
  STATE__ is an anaphoric variable available to all breakpoint macros. Ends with
  __ to avid conflicts with user locals and to signify that it's an internal
  variable which is cleaned in `sanitize-env' along other clojure's
  temporaries."
  {:style/indent 1}
  [& body]
  ;; NOTE: *msg* is the message that instrumented the function,
  `(let [~'STATE__ {:msg ~(let [{:keys [code id file line column ns]} *msg*]
                            (-> {:code code
                                 ;; Passing clojure.lang.Namespace object
                                 ;; as :original-ns breaks nREPL in bewildering
                                 ;; ways.
                                 :original-id id, :original-ns (str (or ns *ns*))
                                 :file file, :line line, :column column}
                                ;; There's an nrepl bug where the column starts counting
                                ;; at 1 if it's after the first line. Since this is a
                                ;; top-level sexp, a (= col 1) is much more likely to be
                                ;; wrong than right.
                                (update :column #(if (= % 1) 0 %))))
                    :forms @*tmp-forms*}]
     ~@body))

(defmacro breakpoint-with-initial-debug-bindings
  {:style/indent 1}
  [form dbg-state original-form]
  `(with-initial-debug-bindings
     (breakpoint-if-interesting
       ~form ~dbg-state ~original-form)))

(defn break
  "Breakpoint function.
  Send the result of form and its coordinates to the client and wait for
  response with `read-debug-command`'."
  [coor val locals STATE__]
  (cond
    (skip-breaks? coor (get-in STATE__ [:msg :code])) val
    ;; The length of `coor` is a good indicator of current code
    ;; depth.
    (= (:mode @*skip-breaks*) :trace)
    (do (print-step-indented (count coor) (get-in STATE__ [:forms coor]) val)
        val)
    ;; Most common case - ask for input.
    :else
    (read-debug-command val (assoc (:msg STATE__)
                                   :debug-value (pr-short val)
                                   :coor coor
                                   :locals locals))))

(defn apply-instrumented-maybe
  "Apply var-fn or its instrumented version to args."
  [var-fn args coor STATE__]
  (let [stepin (step-in? var-fn coor (get-in STATE__ [:msg :code]))]
    (apply (if stepin
             (::instrumented (meta var-fn))
             var-fn)
           args)))

(defmacro expand-break
  "Internal macro to avoid code repetition in `breakpoint-if-interesting`."
  [form {:keys [coor]} original-form]
  (let [val-form (if (looks-step-innable? form)
                   (let [[fn-sym & args] form]
                     `(apply-instrumented-maybe (var ~fn-sym) [~@args] ~coor ~'STATE__))
                   form)
        locals (when *do-locals*
                 (sanitize-env &env))]
    ;; Keep original forms in a separate atom to save some code
    ;; size. Unfortunately same trick wouldn't work for locals.
    (swap! *tmp-forms* assoc coor original-form)
    `(break ~coor ~val-form ~locals ~'STATE__)))

(def irrelevant-return-value-forms
  "Set of special-forms whose return value we don't care about.
  When instrumenting, these will not be wrapped in a breakpoint."
  '#{def fn* deftype* reify* monitor-enter monitor-exit})

(defmacro breakpoint-if-interesting
  "Wrap form in a breakpoint if it looks interesting.
  Uninteresting forms are symbols that resolve to `clojure.core`
  (taking locals into account), and sexps whose head is present in
  `irrelevant-return-value-forms`. Used as :breakfunction in `tag-form`."
  [form {:keys [coor] :as dbg-state} original-form]
  (if (or (and (symbol? form)
               (not (contains? &env form))
               (try
                 (-> (resolve form) meta :ns
                     ns-name #{'clojure.core 'schema.core})
                 (catch Exception _ nil)))
          (and (seq? form)
               (irrelevant-return-value-forms (first form))))
    form
    (let [condition (:break/when (meta form))]
      (if condition
        ;; If there is a condition and it is falsy, we need to skip
        ;; the current level (:deeper than parent coor), but only
        ;; once. Next time, we need to test the condition again.
        `(let [old-breaks# @*skip-breaks*]
           (when-not ~condition
             (skip-breaks! :deeper ~(vec (butlast coor)) (:code (:msg ~'STATE__)) false))
           (try
             (expand-break ~form ~dbg-state ~original-form)
             (finally (reset! *skip-breaks* old-breaks#))))
        `(expand-break ~form ~dbg-state ~original-form)))))

;;; ## Data readers
;;
;; Set in `src/data_readers.clj`.
(defn breakpoint-reader
  "#break reader. Mark `form` for breakpointing."
  [form]
  (ins/tag-form form #'breakpoint-with-initial-debug-bindings))

(defn debug-reader
  "#dbg reader. Mark all forms in `form` for breakpointing.
  `form` itself is also marked."
  [form]
  (ins/tag-form (ins/tag-form-recursively form #'breakpoint-if-interesting)
                #'breakpoint-with-initial-debug-bindings))

(defn instrument-and-eval [form]
  (let [form1 (ins/instrument-tagged-code form)]
    ;; (ins/print-form form1 true false)
    (try
      (binding [*tmp-forms* (atom {})]
        (eval form1))
      (catch java.lang.RuntimeException e
        (if (re-matches #".*Method code too large!.*" (.getMessage e))
          (do (println "WARNING: Method code too large!"
                       "Locals and evaluation in local context won't be available.")
              ;; Re-try without locals
              (binding [*tmp-forms* (atom {})
                        *do-locals* false]
                (eval form1)))
          (throw e))))))

;;; ## Middleware
(defn- maybe-debug
  "Return msg, prepared for debugging if code contains debugging macros."
  [{:keys [code ns] :as msg}]
  ;; The best way of checking if there's a #break reader-macro in
  ;; `code` is by reading it, in which case it toggles `has-debug?`.
  (let [has-debug?  (atom false)
        ;; Don't return nil in reader (https://dev.clojure.org/jira/browse/CLJ-1138)
        fake-reader (fn [x] (reset! has-debug? true) x)]
    (binding [*ns* (find-ns (symbol (or ns "user")))
              *data-readers* (->> (repeat fake-reader)
                                  (interleave '[dbg break light])
                                  (apply assoc *data-readers*))]
      (try
        ;; new-line in REPL always throws; skip for debug convenience
        (when (> (count code) 3)
          (read-string {:read-cond :allow} code))
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

(defn handle-debug
  [handler {:keys [op input session] :as msg}]
  (case op
    "eval" (do (when (instance? clojure.lang.Atom session)
                 (swap! session assoc #'*skip-breaks* (atom nil)))
               (handler (maybe-debug msg)))
    "debug-instrumented-defs" (instrumented-defs-reply msg)
    "debug-input" (when-let [pro (@promises (:key msg))]
                    (deliver pro input))
    "init-debugger" (initialize msg)
    ;; else
    (handler msg)))
