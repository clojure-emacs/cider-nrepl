(ns cider.nrepl.middleware.debug
  "Expression-based debugger for clojure code"
  {:author "Artur Malabarba"}
  (:require [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [cider.nrepl.middleware.util.instrument :as ins]
            [cider.nrepl.middleware.util.cljs :as cljs]
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
  "Boolean or vector to determine whether to skip a breakpoint.
  Don't set or examine this directly, it is bound in the session
  binding map, use `skip-breaks!` and `skip-breaks?` instead.
  Its value is discarded at the end each eval session."
  nil)

(defn skip-breaks?
  "True if the breakpoint at coordinates should be skipped.
  If *skip-breaks* is true, return true.
  If *skip-breaks* is a vector of integers, return true if coordinates
  are deeper than this vector."
  [coordinates]
  (when-let [sb (@(:session *msg*) #'*skip-breaks*)]
    (or
     ;; From :continue, skip everything.
     (true? sb)
     ;; From :out, skip some breaks.
     (let [parent (take (count sb) coordinates)]
       (and (= sb parent)
            (> (count coordinates) (count parent)))))))

(defn skip-breaks!
  "Set the value of *skip-breaks* in the session binding map."
  [bool-or-vec]
  (swap! (:session *msg*) assoc #'*skip-breaks* bool-or-vec))

(defn- abort!
  "Stop current eval thread.
  This does not quit the repl, it only interrupts an eval operation."
  []
  (transport/send (:transport *msg*) (response-for *msg* :value 'QUIT))
  (transport/send (:transport *msg*) (response-for *msg* :status :done))
  (.stop (:thread (meta (:session *msg*)))))

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

(def ^:dynamic *locals*
  "Bound by the `breakpoint` macro to the local &env."
  {})

(defn- locals-for-message
  "Prepare a map of local variables for sending through the repl.
  This involves removing any keys whose name looks like it was
  autogenerated by a macro, and turning keys and values to strings."
  [m]
  (map (partial map pr-str)
       (remove (fn [[k]] (re-find  #"_" (name k))) m)))

(defn- read-debug
  "Like `read`, but reply is sent through `debugger-message`.
  type is sent in the message as :input-type."
  [extras type prompt]
  (let [key (random-uuid-str)
        input (promise)]
    (swap! promises assoc key input)
    (->> (assoc extras
                :status :need-debug-input
                :locals (locals-for-message *locals*)
                :key key
                :prompt prompt
                :input-type type)
         (response-for @debugger-message)
         (transport/send (:transport @debugger-message)))
    @input))

(defn- eval-with-locals
  "`eval` form wrapped in a let of the *locals* map."
  [form]
  (eval
   `(let ~(vec (mapcat #(list % `(*locals* '~%)) (keys *locals*)))
      ~form)))

(defn- read-debug-eval-expression
  "Read and eval an expression from the client.
  extras is a map to be added to the message, and prompt is added into
  the :prompt key."
  ([prompt extras] (read-debug-eval-expression prompt extras nil))
  ([prompt extras code]
   (eval-with-locals (or code (read-debug extras :expression prompt)))))

(defn read-debug-command
  "Read and take action on a debugging command.
  Ask for one of the following debug commands using `read-debug`:

    next: Return value.
    continue: Skip breakpoints for the remainder of this eval session.
    out: Skip breakpoints in the current sexp.
    inject: Evaluate an expression and return it.
    eval: Evaluate an expression, display result, and prompt again.
    quit: Abort current eval session.

  Response received can be any one of these values. It can also be a
  map whose :response entry is one of these values, which can thus be
  used to provide aditional parameters. For instance, if this map has
  a :code entry, its value is used for operations such as :eval, which
  would otherwise interactively prompt for an expression."
  [value extras]
  (let [commands (if (cljs/grab-cljs-env *msg*)
                   [:next :continue :out :inject :eval :quit]
                   [:next :continue :out :inject :eval :quit])
        prompt (apply str (map #(let [[f & r] (name %)]
                                  (apply str " (" f ")" r))
                               commands))
        response-raw (read-debug extras commands prompt)
        {:keys [code response]} (if (map? response-raw) response-raw
                                    {:response response-raw})]
    (case response
      :next     value
      :continue (do (skip-breaks! true) value)
      :out      (do (skip-breaks! (butlast (:coor extras))) value)
      :inject   (read-debug-eval-expression "Expression to inject: " extras code)
      :eval     (let [return (read-debug-eval-expression "Expression to evaluate: " extras code)]
                  (read-debug-command value (assoc extras :debug-value (pr-str return))))
      :quit     (abort!))))

;;; ## High-level functions
(defmacro breakpoint
  "Send value and coordinates to the client through the debug channel.
  Sends a response to the message stored in debugger-message."
  [value coor]
  `(binding [*locals* ~(sanitize-env &env)]
     (let [val# ~value]
       (cond
         (skip-breaks? ~coor) val#
         (not (seq @debugger-message)) (do (println "Debugger not initialized")
                                           (skip-breaks! true)
                                           val#)
         :else (read-debug-command
                val#
                ;; This *msg* is evaluated at compile-time, so it's
                ;; the message that instrumented the function, not the
                ;; message that led to its evaluation.
                (assoc ~(let [{:keys [code id file point]} *msg*]
                          {:code code, :original-id id, :coor coor
                           :file file, :point point})
                       :debug-value (pr-str val#)))))))

;;; Data readers
(defn breakpoint-reader
  "#break reader. Mark `form` for breakpointing."
  [form]
  (ins/with-meta-safe form {:cider-breakfunction #'breakpoint}))

(defn debug-reader
  "#dbg reader. Mark all forms in `form` for breakpointing.
  `form` itself is also marked."
  [form]
  (walk/postwalk breakpoint-reader form))

(defn instrument-and-eval [form]
  (eval (ins/instrument-tagged-code form)))

;;; Middleware setup
(defn- maybe-debug
  "Return msg, prepared for debugging if code contains debugging macros."
  [{:keys [code session] :as msg}]
  (when (instance? clojure.lang.Atom session)
    (swap! session update-in [#'*data-readers*] assoc
           'dbg #'debug-reader 'bp  #'breakpoint-reader))
  ;; The best way of checking if there's a #break reader-macro in
  ;; `code` is by reading it, in which case it toggles `has-debug?`.
  (let [has-debug? (atom false)
        fake-reader (fn [x] (reset! has-debug? true) nil)]
    (binding [*data-readers* {'dbg fake-reader, 'bp fake-reader}]
      (try
        (read-string code)
        (catch Exception e))
      (if @has-debug?
        ;; Technically, `instrument-and-eval` acts like a regular eval
        ;; if there are no debugging macros. But we still only use it
        ;; when we know it's necessary.
        (assoc msg :eval "cider.nrepl.middleware.debug/instrument-and-eval")
        ;; If there was no reader macro, fallback on regular eval.
        msg))))

(defn- initialize
  "Initialize the channel used for debug-input requests."
  [msg]
  (when-let [stored-message @debugger-message]
    (transport/send (:transport stored-message)
                    (response-for stored-message :status :done)))
  ;; The above is just bureaucracy. The below is important.
  (reset! debugger-message msg))

(defn wrap-debug [h]
  (fn [{:keys [op input] :as msg}]
    (case op
      "eval" (h (maybe-debug msg))
      "debug-input" (when-let [pro (@promises (:key msg))]
                      (swap! promises dissoc  (:key msg))
                      (try (deliver pro (read-string input))
                           (catch Exception e
                             (when-not (realized? pro)
                               (deliver pro :quit))
                             (throw e)))
                      (transport/send (:transport msg)
                                      (response-for msg :status :done)))
      "init-debugger" (initialize msg)
      ;; else
      (h msg))))

(set-descriptor!
 #'wrap-debug
 (cljs/requires-piggieback
  {:expects #{"eval"}
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
    "debug-middleware"
    {:doc "Debug a code form or fall back on regular eval."
     :requires {"id" "A message id that will be responded to when a breakpoint is reached."
                "code" "Code to debug, there must be a #dbg or a #break reader macro in it, or nothing will happen."
                "file" "File where the code is located."
                "ns" "Passed to \"eval\"."
                "point" "Position in the file where the provided code begins."}
     :returns {"status" "\"done\" if the message will no longer be used, or \"need-debug-input\" during debugging sessions"}}}}))
