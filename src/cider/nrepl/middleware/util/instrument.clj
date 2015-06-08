(ns cider.nrepl.middleware.util.instrument
  "Generic instrumentation for clojure code"
  {:author "Artur Malabarba"}
  (:require [cider.nrepl.middleware.info :refer [resolve-special info-clj]]))

;;;; # Instrumentation
;;; The following code is responsible for automatic instrumentation.
;;; This involves:
;;;    - knowing what's interesting and what's not,
;;;    - walking though the code,
;;;    - distinguishing function calls from macros,
;;;    - distinguishing between the different collections.

;;;; ## Auxiliary defs
(def irrelevant-return-value-macros
  "Set of macros whose return value we don't care about.
  When instrumenting, these will not be wrapped in a breakpoint."
  #{'defn- 'defn 'def 'fn 'fn*
    'defmacro 'defmethod 'defmulti
    'definline 'definterface})

;;; We'll probably want to expand this variable. It is used to
;;; determine uninteresting symbols.
(def core-publics
  "Set of all public symbols from the clojure.core namespace."
  (into #{} (map second (ns-publics 'clojure.core))))

(defn- interesting-symbol?
  "Non-nil if the value of symbol might be interesting.
  These are symbols we wrap breakpoints around. An example of
  uninsteresting symbols is a keyword or the name of a built-in
  function."
  [symbol]
  (not (or (keyword? symbol)
           (when-let [resolved (ns-resolve *ns* symbol)]
             (core-publics resolved)))))

;;; Surprisingly, (list? `(inc 1)) is false.
(defn- listy?
  "List or clojure.lang.Cons."
  [x]
  (or (list? x)
      (instance? clojure.lang.Cons x)))

(def special-arglist-symbols
  "Map of symbols to which we attribute special arglists.
  Used only for the purposed of instrumenting on special symbols which
  don't have arglists (such as `if` or `try`), or for macros whose
  usual arglist we wouldn't understand.

  This can also be used to disable instrumenting of a form by setting
  its arglist to ([]).

  Finally, if the key's value is a symbol, it is taken as an alias and
  we use this symbol's arglist."

  '{;; Possible in theory, but not supported yet.
    try ([& form]),

    ;; More sophisticated cases with badly written arglists.
    defn- defn,
    fn ([name? [params*] exprs*] [name? ([params*] exprs*) +]),

    ;; condp, cond, case, cond->, cond->>, all use different meanings
    ;; for the "clause" argument. We do the best we can in the
    ;; `specifier-map` below, but we need to override a few here too.
    ;; case clauses are of the form-expression type.
    case ([expr fe-clause* expr?]),
    ;; cond-> clauses are of the expression-form type.
    cond-> ([expr & ef-clause]),
    cond->> cond->})

(defn- macro-arglists
  "Return a list of possible arglist vectors for symbol."
  [symbol]
  (if-let [special (special-arglist-symbols symbol)]
    (if (symbol? special)
      (macro-arglists special)
      special)
    (let [metadata (if (var? symbol)
                     (meta symbol)
                     (info-clj (ns-name *ns*) symbol))]
      ;; :debugspec let's people define their own macros in a way we
      ;; can instrument it (if they don't want to use the arglist).
      (or (:debugspec metadata)
          ;; Then we look for an arglist.
          (:arglists metadata)
          ;; If it doesn't have an arglist, it's probably a special
          ;; form. Try to construct an arglist from :forms.
          (map (comp vec rest)
               (filter seq? (:forms metadata)))))))

;;; Because of the way we handle arglists, we need the modifiers such
;;; as + or & to come before the argument they're affecting. That's
;;; not true of +, so we'll use this function to patch that.
;;;    See, for instance, (macro-arglists 'defn).
(defn- reorder-+
  "If arglist contains a +, move it one position to the front."
  [arglist]
  (if (< (count arglist) 2)
    arglist
    (loop [past      []
           [f & r] arglist]
      (if (seq r)
        (let [s (first r)]
          (if (= '+ s)
            (if (seq (rest r))
              (recur (conj past s f) (rest r))
              (conj past s f))
            (recur (conj past f) r)))
        (conj past f)))))

;;;; ## Instrumentation Specifics
;;; Here starts the actual instrumentation code. All the
;;; `instrument-*` functions take an extra first argument `ex`. This
;;; is a map holding arbitrary information, it is passed around
;;; through all of the functions and eventually makes it to the
;;; breakpoint. The map is unchanged except for one key, :coor. The
;;; :coor and the :breakfunction keys MUST be present in the map.
;;;
;;; The value of the :breakfunction key is a function which will be
;;; used as the breakpoint.
;;; 
;;; The value of the :coor key is a vector of integers (coordinates)
;;; which is gradually built and passed on as the form is being
;;; walked. The purpose is that they will be inserted in the
;;; breakpoint, and the client can later use these coordinates to
;;; place point in the right sexp during debugging.

;;; The top-level instrumenting function is `instrument-and-eval`.
;;; Cider calls this function, where `ex` holds information, such as
;;; file-name, which is then stored in the breakpoint. This function,
;;; also takes care of adding a :coor key to `ex`.
;;;
;;; Each of the other `instrument-*` functions is responsible by
;;; calling subordinates and incrementing the coordinates vector if
;;; necessary. Each element added to :coor is an integer, it
;;; represents the position of this sexp inside its parent. As an
;;; example, a coordinate vector of [3 2 0] means:
;;;     - enter this sexp and move forward three times,
;;;     - enter this sexp and move forward twice,
;;;     - enter this sexp.

;;;; ### Instrumenting Macros and Special forms
;;; Here, we try to decide how to instrument macros and special-forms.
;;; Unlike function calls, we can't just look at each argument
;;; separately.
;;;
;;; In order to decide how to read the macro, we look at its argument
;;; list. This works reasonably well for built-in stuff. Of course,
;;; since macro arglists were obviously not written with Cider in
;;; mind, we need to stay on the careful side. That is, we don't
;;; instrument something unless we're SURE we know what it means.
;;;
;;; The worst case scenario is that we don't understand an arglist,
;;; and so we just don't instrument the inside of the macro (we'll
;;; still wrap the whole form in a breakpoint).
(declare instrument)
(def always-1 (constantly 1))
(defn- instrument-nothing [ex forms] forms)

(defn- specifier-match-bindings
  [[bind & _]]
  (and (vector? bind)
       (even? (count bind))
       1))

(defn- instrument-bindings
  ([ex bindings]
   (if (seq bindings)
     (instrument-bindings ex bindings 1)
     bindings))
  ([{:keys [coor] :as ex} [bind expr & more-binds] expr-pos]
   (into [bind (instrument (assoc ex :coor (conj coor expr-pos)) expr)]
         (when (seq more-binds)
           (instrument-bindings ex more-binds (+ expr-pos 2))))))

(defn- instrument-map
  "Instrument a map collection.
  The rules of what gets instrumented are the same as for any
  collection, but maps need special handling because they're read as
  tables but written as a linear collection, and this affects the
  `coor` variable."
  [{:keys [coor] :as ex} themap]
  (let [out (map (fn [pos [a b]]
                   [(instrument (assoc ex :coor (conj coor (* 2 pos))) a)
                    (instrument (assoc ex :coor (conj coor (inc (* 2 pos)))) b)])
                 (range (count themap))
                 themap)]
    (into {} out)))

(defn- instrument-next-arg
  [ex [form & forms]]
  (cons (instrument ex form)
        forms))

(defn- instrument-all-args
  [{:keys [coor] :as ex} forms]
  (let [n (last coor)
        coor (vec (butlast coor))]
    (map #(instrument (assoc ex :coor (conj coor (+ n %2))) %1)
         forms
         (range (count forms)))))

(defn- instrument-all-but-first-arg
  [{:keys [coor] :as ex} [form & forms]]
  (let [n (last coor)
        coor (vec (butlast coor))]
    (cons form
          (map #(instrument (assoc ex :coor (conj coor (+ n 1 %2))) %1)
               forms
               (range (count forms))))))

(defn- instrument-second-arg
  [{:keys [coor] :as ex} [form1 form2 & forms]]
  (cons form1
        (let [n (last coor)
              coor (vec (butlast coor))]
          (cons (instrument (assoc ex :coor (conj coor (inc n))) form2)
                forms))))

(defn- instrument-like-fn
  [{:keys [coor] :as ex} [[name & fn-tail] & forms]]
  ;; Pretend it's an `fn`.
  (let [instrumented (instrument ex (cons 'fn fn-tail))]
    ;; Dump the `fn` head and replace witht the actual name.
    (cons (cons name (rest instrumented))
          forms)))

(defn- match-like-fn
  "Return 1 if the first element of the arg looks like a fn."
  [[f]]
  (and (listy? f)
       (> (count f) 2)
       (symbol? (first f))
       (vector? (second f))
       1))

(def instrument-two-args
  #(instrument-next-arg %1 (instrument-second-arg %1 %2)))

(declare instrument-special-form-try)
(def specifier-map
  "Map between specifiers and [matcher handler] functions pairs.
  Each matcher should take a list of forms and return the number of
  elements at the start of the list that the specifier represents. It
  will never be called on an empty list, so it can assume there's at
  least one element.

  If it can't match any of the first forms, it must return nil. Unless
  the specifier is allowed to be empty (see `body`, for instance), in
  which case it should return 0.

  Each handler is a function that takes two arguments, an extras
  map (see explanation above on them) and the same list of forms. The
  handler should return a list of forms with the same length, but
  where the first n elements have been instrumented, where n is the
  number previously returned by the matcher. The handler is not called
  if the matcher returned nil."

  {;; Safe to instrument
   "expr" [always-1 instrument-next-arg]
   "init" [always-1 instrument-next-arg]
   "pred" [always-1 instrument-next-arg]
   "test" [always-1 instrument-next-arg]
   "then" [always-1 instrument-next-arg]
   "else" [always-1 instrument-next-arg]
   "x"    [always-1 instrument-next-arg]
   "next" [always-1 instrument-next-arg]
   ;; Match everything.
   "body" [count instrument-all-args]
   ;; Not safe or not meant to be instrumented
   "form"    [always-1 instrument-nothing]
   "oldform" [always-1 instrument-nothing]
   "params"  [always-1 instrument-nothing]
   "args"    [always-1 instrument-nothing]
   "name"    [(fn [[f]] (if (symbol? f) 1)) instrument-nothing]
   "symbol"  [(fn [[f]] (if (symbol? f) 1)) instrument-nothing]
   ;; Complicated
   "bindings" [specifier-match-bindings
               (fn [ex [bindings & forms]]
                 (cons (instrument-bindings ex bindings) forms))]
   "docstring" [#(when (string? (first %)) 1) instrument-nothing]
   "string"    [#(when (string? (first %)) 1) instrument-nothing]
   "map"       [#(when (map? (first %)) 1)    instrument-next-arg]
   "f"         [match-like-fn instrument-like-fn]
   "fn-tail"   [#(when (vector? (first %)) (count %))
                instrument-all-but-first-arg]
   "dispatch-fn" [(fn [[[f & r]]]
                    (when (instrument-special-form-try [] f r) 1))
                  instrument-next-arg]
   ;; Clauses are a mess. cond, condp, case, all take them to mean
   ;; different things. And don't get me started on cond->.
   ;; I hereby declare `clause` to mean "instrument anything".
   "clause"    [always-1 instrument-next-arg]
   ;; Expression-Form type clauses instrument every odd argument.
   "ef-clause" [#(if (> (count %) 1) 2) instrument-next-arg]
   ;; Form-Expression type clauses instrument every even argument.
   "fe-clause" [#(if (> (count %) 1) 2) instrument-second-arg]})

(defn- specifier-destructure
  "Take a symbol specifier and return a vector description.
  Returns [mh-pair optional multiples]

  mh-pair is the specifier's matcher-handler pair, as found in
  specifier-map.

  If optional is non-nil the specifier is allowed to match nothing. If
  multiples is non-nil, the specifier can match any number of times."
  [spec]
  (let [spec-name (name spec)
        opt (.endsWith spec-name "?")
        mult (.endsWith spec-name  "*")
        spec-name (if (or opt mult)
                    (subs spec-name 0 (dec (count spec-name)))
                    spec-name)
        spec-vec (specifier-map spec-name)]
    [(or spec-vec
         (specifier-map
          (if (.endsWith spec-name "s")
            (subs spec-name 0 (dec (count spec-name)))
            (str spec-name "s")))
         (and (.endsWith spec-name "-symbol") (specifier-map "symbol"))
         (and (.endsWith spec-name "-string") (specifier-map "string"))
         (and (.endsWith spec-name "-map") (specifier-map "map"))
         [always-1 instrument-nothing])
     opt mult]))

(defn- instrument-forms-per-matcher-handler
  "Like instrument-forms-per-specifier, but takes [matcher handler]."
  [{:keys [coor] :as ex} n forms [matcher handler :as spec] opt mult]
  (cond
    ;; m = number of forms spec can handle.
    (seq forms) (if-let [m (matcher forms)]
                  (let [instrumented (handler (assoc ex :coor (conj coor n)) forms)]
                    (if (not mult)
                      [m instrumented]
                      (let [[m2 instrumented2]
                            (instrument-forms-per-matcher-handler
                             ex (+ n m) (drop m instrumented) spec opt mult)]
                        [(+ m m2) (concat (take m instrumented) instrumented2)])))
                  (when (or opt mult) [0 forms]))
    ;; `matcher` is allowed to assume `forms` has at least one
    ;; element, so we handle the empty case here.
    (or opt mult) [0 forms]
    :else nil))

(declare instrument-args-given-arglist)
(defn- instrument-forms-per-specifier
  "Instrument a list of forms according to specifier.
  Return nil if specifier is incompatible with forms.
  Otherwise, return a vector [n newforms], which means the first n
  elements of newforms have been instrumented.

  See `arglist-reductor` for details.

  Unlike other instrument-* functions, this one might be called with
  only part of a full sexp (the full sexp is the list of a arguments
  in a macro call), so n is an integer indicating the position that
  the first element in forms takes in the full sexp. This will usually
  be incremented before delegating to other instrument-* functions, to
  also account for the macro name."
  [{:keys [coor] :as ex} n forms spec]
  (cond
    ;; Most specs are symbols.
    (symbol? spec) (apply instrument-forms-per-matcher-handler
                          ex (inc n) forms (specifier-destructure spec))
    ;; Some arglists have maps.
    (map? spec) [1 (cons
                    (instrument-map (assoc ex :coor (conj coor (inc n)))
                                    (first forms))
                    (rest forms))]
    ;; See `defn` for an example of arglist with both lists and vectors.
    (list? spec)
    (when (list? (first forms))
      (when-let [instrumented
                 (instrument-args-given-arglist
                  (assoc ex :coor (conj coor (inc n))) (first forms) spec)]
        [1 (cons instrumented (rest forms))]))
    (vector? spec)
    (when (vector? (first forms))
      (when-let [instrumented
                 (instrument-args-given-arglist
                  (assoc ex :coor (conj coor (inc n))) (apply list (first forms)) spec)]
        [1 (cons (vec instrumented) (rest forms))]))

    ;; Anything else, return nil to indicate we don't handle it.
    :else nil))

(def verbose-debug
  "If true, print information while parsing arglists."
  false)

(defn- arglist-reductor
  "Try to instrument a list of forms according to specifier.
  Starts with the n-th element. Returns [m new-form-list], where m is
  the position of the first form that hasn't yet been handled
  --anything between n and (count form-list), inclusives.

  Specifier is a symbol used in arglists. mod can be + or &, and it
  modifies how many times the specifier can match.

  Return nil when the instrumentation can't be done."
  [ex so-far specifier]
  ;; If something didn't match earlier, just return nil.
  (when so-far
    (let [[n mod form-list] so-far
          head (take n form-list)
          tail (drop n form-list)
          result
          (cond
            (= specifier '&) [n '& form-list]
            (= specifier '+) [n '+ form-list]
            (= specifier '*) [n '* form-list]
            ;; & and * mean this arg can be absent.
            (and ('#{& *} mod) (empty? tail)) so-far

            ;; Actually try to match.
            mod (when-let [[m newtail]
                           (instrument-forms-per-specifier ex n tail specifier)]
                  (if (> (count form-list) (+ m n))
                    (if (= mod '&)
                      ;; & has to match until the end.
                      (arglist-reductor
                       ex [(+ m n) '& (doall (concat head newtail))] specifier)
                      ;; '+ matches while it can.
                      (or (arglist-reductor
                           ex [(+ m n) '* (doall (concat head newtail))] specifier)
                          [(+ m n) nil (doall (concat head newtail))]))
                    [(+ m n) nil (doall (concat head newtail))]))

            ;; Specifier without modifiers.
            :else (when-let [[m newtail]
                             (instrument-forms-per-specifier ex n tail specifier)]
                    [(+ m n) nil (doall (concat head newtail))]))]
      (when verbose-debug
        (print "    Spec" specifier)
        (if-let [[m] result]
          (do (when mod (print " with " mod))
              (print " matched ")
              (if (> m n)
                (doall (apply pr (drop n (take m form-list))))
                (print "nothing.")))
          (println " didn't match!"))
        (println))
      result)))


(defn- instrument-args-given-arglist
  "Try to instrument form assuming its rest follows arglist."
  [ex args arglist]
  (when verbose-debug
    (print "Trying: ")
    (prn arglist))
  (let [[n _ result]
        (reduce (partial arglist-reductor ex) [0 nil args] (reorder-+ arglist))]
    (when (= n (count args))
      (when verbose-debug (println "Success!"))
      result)))

(defn- instrument-special-form-try
  [ex name args]
  (some (partial instrument-args-given-arglist ex args)
        (macro-arglists name)))

(defn- instrument-special-form
  "Instrument form representing a macro call or special-form."
  [ex [name & args :as form]]
  (if-let [result (instrument-special-form-try ex name args)] ;; (first `(defn))
    (cons name result)
    (do (binding [*print-length* 4
                  *print-level*  1]
          (println "Failed to instrument " form ", please file a bug report"))
        form)))

;;;; ## Instrumenting Functions and Collections
;;; This part is quite simple, most of the code is devoted to checking
;;; form-types and special cases. The idea here is that we walk
;;; through collections and function arguments looking for interesting
;;; things around which we'll wrap a breakpoint. Interesting things
;;; are most function-forms and vars satisfying `interesting-symbol?`.
(defn- instrument-coll
  "Instrument a general collection."
  [{:keys [coor] :as ex} coll]
  (let [out (map #(instrument (assoc ex :coor (conj coor %1)) %2)
                 (range (count coll))
                 coll)]
    (if (listy? coll)
      out
      (into (empty coll) out))))

(defn- instrument-function-call
  "Instrument a regular function call sexp.
  This must be a sexp that starts with a symbol which is not a macro
  nor a special form.
  This includes regular function forms, like `(range 10)`, and also
  includes calls to Java methods, like `(System/currentTimeMillis)`."
  [{:keys [coor] :as ex} [name & args]]
  (cons name
        (map #(instrument (assoc ex :coor (conj coor %1)) %2)
             (range 1 (inc (count args)))
             args)))

(defn with-break
  "Return form and ex wrapped in a breakpoint.
  If function is given, use it to instrument form before wrapping. The
  breakpoint is given by ex's :breakfunction key."
  ([function form ex]
   (with-break (function ex form) ex))
  ([form ex]
   (if (seq (:coor ex))
     `(~(:breakfunction ex) ~form ~ex)
     ;; Top-level sexps are rarely interesting.
     form)))

(defn- contains-recur?
  "Return true if form is not a `loop` and a `recur` is found in it."
  [form]
  (if (listy? form)
    (condp = (first form)
      'recur true
      'loop  false
      (some contains-recur? (rest form)))))

(defn- dont-break?
  "Return true if it's NOT ok to wrap form in a breakpoint.
  Expressions we don't want to wrap are those containing a `recur`
  form, and those whose `name` is contained in
  `irrelevant-return-value-macros`."
  [[name :as form]]
  (or (irrelevant-return-value-macros name)
      (contains-recur? form)))

(defn- instrument-function-like-form
  "Instrument form representing a function/macro call or special-form."
  [ex [name & args :as form]]
  (if-not (symbol? name)
    ;; If the car is not a symbol, nothing fancy is going on and we
    ;; can instrument everything.
    (with-break instrument-coll form ex)
    (let [name (or (ns-resolve *ns* name) name)]
      (if (or (resolve-special name)
              (:macro (meta name)))
        ;; If macro or special form, thread with care.
        (if (dont-break? form)
          (instrument-special-form ex form)
          (with-break instrument-special-form form ex))
        ;; Otherwise, probably just a function. Just leave the
        ;; function name and instrument the args.
        (with-break instrument-function-call form ex)))))

(defn instrument
  "Walk through form and return it instrumented with breakpoints.
  ex is a map of extra information, it is passed to the created
  breakpoints and its :coor value keeps track of how to reach the
  given breakpoint inside the source code."
  [ex form]
  (condp #(%1 %2) form
    ;; Function call, macro call, or special form.
    listy? (doall (instrument-function-like-form ex form))
    symbol? (if (interesting-symbol? form)
              (with-break form ex)
              form)
    ;; We just can't walk through a set or a map, as the order is unstable.
    set? (with-break form ex)
    map? (with-break form ex)
    ;; Other coll types are safe, so we go inside them and only
    ;; instrument what's interesting.
    ;; Do we also need to check for seq?
    coll? (doall (instrument-coll ex form))
    ;; Other things are uninteresting, literals or unreadable objects.
    form))
