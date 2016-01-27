(ns cider.nrepl.middleware.util.instrument
  "Generic instrumentation for clojure code"
  {:author "Artur Malabarba"}
  (:require [clojure.walk :as walk]))

;;;; # Instrumentation
;;; The following code is responsible for automatic instrumentation.
;;; This involves:
;;;    - knowing what's interesting and what's not,
;;;    - walking though the code,
;;;    - distinguishing function calls from special-forms,
;;;    - distinguishing between the different collections.

;;;; ## Auxiliary defs
(def irrelevant-return-value-forms
  "Set of macros whose return value we don't care about.
  When instrumenting, these will not be wrapped in a breakpoint."
  '#{def fn* deftype* reify* quote
     catch finally
     monitor-enter monitor-exit})

;;; We'll probably want to expand this variable. It is used to
;;; determine uninteresting symbols.
(defn with-meta-safe
  "Non-throwing version of (vary-meta obj merge meta)."
  [obj meta]
  (try
    (vary-meta obj merge meta)
    (catch Exception e obj)))

;;; Surprisingly, (list? `(inc 1)) is false.
(defn- listy?
  "Check if `x` is a list, LazySeq or Cons."
  [x]
  (or (list? x)
      (instance? clojure.lang.LazySeq x)
      (instance? clojure.lang.Cons x)))

;;;; ## Instrumentation
;;; The top-level instrumenting function is `read-and-instrument`. See
;;; its doc for more information.
;;;
;;; Each of the other `instrument-*` functions is responsible for
;;; calling subordinates and incrementing the coordinates vector if
;;; necessary.

;;;; ### Instrumenting Special forms
;;; Here, we implement instrumentation of special-forms on a
;;; case-by-case basis. Unlike function calls, we can't just look at
;;; each argument separately.
(declare instrument)

(defn- instrument-coll
  "Instrument a collection."
  [coll]
  (with-meta-safe
    (walk/walk instrument identity coll)
    (meta coll)))

(defn instrument-case-map
  "Instrument the map that is 5th arg in a `case*`."
  [args]
  (into {} (map (fn [[k [v1 v2]]] [k [v1 (instrument v2)]])
                args)))

(defn list-instrumented-defs [ns]
  (let [ns (if (instance? clojure.lang.Namespace ns) ns
               (find-ns (symbol ns)))]
    (->> (ns-interns ns)
         (filter (comp :cider-instrumented meta second))
         (map first))))

(defn- instrument-special-form
  "Instrument form representing a macro call or special-form."
  [[name & args :as form]]
  (cons name
        ;; We're dealing with some low level stuff here, and some of
        ;; these internal forms are completely undocumented, so let's
        ;; play it safe and use a `try`.
        (try
          (condp #(%1 %2) name
            '#{if do recur throw finally try new monitor-exit monitor-enter}
            (instrument-coll args)
            '#{quote & var clojure.core/import*} args
            '#{.} `(~(instrument (first args))
                    ~(second args)
                    ~@(instrument-coll (rest (rest args))))
            '#{def} (let [sym (first args)]
                      (list* (if (meta sym)
                               (with-meta-safe sym (assoc (instrument (meta sym))
                                                          :cider-instrumented true))
                               sym)
                             (map instrument (rest args))))
            '#{set!} (list (with-meta-safe (first args) {:cider-instrumented true})
                           (instrument (second args)))
            '#{loop* let* letfn*} (cons (vec (map-indexed (fn [i x] (if (odd? i) (instrument x) x))
                                                          (first args)))
                                        (instrument-coll (rest args)))
            '#{reify* deftype*} (map #(if (listy? %)
                                        (let [[a1 a2 & ar] %]
                                          (with-meta-safe `(~a1 ~a2 ~@(instrument-coll ar)) (meta %)))
                                        %)
                                     args)
            ;; `fn*` has several possible syntaxes.
            '#{fn*} (let [[a1 & [a2 & ar :as a1r]] args]
                      (cond
                        (vector? a1) (cons a1 (instrument-coll a1r))
                        (and (symbol? a1) (vector? a2)) `(~a1 ~a2 ~@(instrument-coll ar))
                        :else
                        (map #(if (listy? %)
                                (with-meta-safe (cons (first %) (instrument-coll (rest %)))
                                  (meta %))
                                %)
                             args)))
            '#{catch} `(~@(take 2 args)
                        ~@(instrument-coll (drop 2 args)))
            ;; Anyone know what a2 and a3 represent? They were always 0 on my tests.
            '#{case*} (let [[a1 a2 a3 a4 a5 & ar] args]
                        `(~a1 ~a2 ~a3 ~(instrument a4) ~(instrument-case-map a5) ~@ar)))
          (catch Exception e
            (binding [*print-length* 4
                      *print-level*  2]
              (println "Failed to instrument" name (doall args)
                       ", please file a bug report: " e))
            args))))

;;;; ### Instrumenting Functions and Collections
;;; This part is quite simple, most of the code is devoted to checking
;;; form-types and special cases. The idea here is that we walk
;;; through collections and function arguments looking for interesting
;;; things around which we'll wrap a breakpoint. Interesting things
;;; are most function-forms and vars.
(defn- instrument-function-call
  "Instrument a regular function call sexp.
  This must be a sexp that starts with a symbol which is not a macro
  nor a special form.
  This includes regular function forms, like `(range 10)`, and also
  includes calls to Java methods, like `(System/currentTimeMillis)`."
  [[name & args]]
  (cons name (instrument-coll args)))

(def verbose-debug false)

(defn with-break
  "Return form wrapped in a breakpoint.
  If function is given, use it to instrument form before wrapping. The
  breakpoint is given by the form's :cider-breakfunction metadata."
  ([function form]
   (with-break (with-meta-safe (function form) (meta form))))
  ([form]
   (let [{:keys [cider-coor cider-breakfunction]} (meta form)]
     (when verbose-debug
       (println "[DBG]" (not (not cider-breakfunction)) cider-coor form))
     (if (and cider-breakfunction (seq cider-coor))
       (list cider-breakfunction form cider-coor)
       ;; If the form is a list and has no metadata, maybe it was
       ;; destroyed by a macro. Try guessing the coor by looking at
       ;; the first element. This fixes `->`, for instance.
       (if (listy? form)
         (let [{:keys [cider-coor cider-breakfunction]} (meta (first form))
               coor (if (= (last cider-coor) 0)
                      (pop cider-coor)
                      cider-coor)]
           (if (and cider-breakfunction (seq cider-coor))
             (list cider-breakfunction form coor)
             form))
         form)))))

(defn- contains-recur?
  "Return true if form is not a `loop` and a `recur` is found in it."
  [form]
  (if (listy? form)
    (case (first form)
      recur true
      loop  false
      (some contains-recur? (rest form)))))

(defn- dont-break?
  "Return true if it's NOT ok to wrap form in a breakpoint.
  Expressions we don't want to wrap are those listed in
  `irrelevant-return-value-forms` and anything containing a `recur`
  form (unless it's inside a `loop`)."
  [[name :as form]]
  (or (irrelevant-return-value-forms name)
      (contains-recur? form)))

(defn- instrument-function-like-form
  "Instrument form representing a function call or special-form."
  [[name :as form]]
  (if-not (symbol? name)
    ;; If the car is not a symbol, nothing fancy is going on and we
    ;; can instrument everything.
    (with-break instrument-coll form)
    (if (special-symbol? name)
      ;; If special form, thread with care.
      (if (dont-break? form)
        (instrument-special-form form)
        (with-break instrument-special-form form))
      ;; Otherwise, probably just a function. Just leave the
      ;; function name and instrument the args.
      (with-break instrument-function-call form))))

(defn instrument
  "Walk through form and return it instrumented with breakpoints.
  Only forms with a :cider-breakfunction metadata will be
  instrumented, and even then only if it makes sense (e.g., the
  binding names in a let form are never instrumented).
  See `read-and-instrument` for more information."
  [form]
  (condp #(%1 %2) form
    ;; Function call, macro call, or special form.
    listy? (doall (instrument-function-like-form form))
    symbol? (with-break form)
    ;; Other coll types are safe, so we go inside them and only
    ;; instrument what's interesting.
    ;; Do we also need to check for seq?
    coll? (doall (instrument-coll form))
    ;; Other things are uninteresting, literals or unreadable objects.
    form))

;;;; ## Pre-instrumentation
;;; The following functions are used to populate with metadata and
;;; macroexpand code before instrumenting it. This is where we
;;; calculate all the :cider-coor vectors. See `read-and-instrument`.
(defn walk-indexed
  "Walk through form calling (f coor element).
  The value of coor is a vector of indices representing element's
  address in the form. Unlike `clojure.walk/walk`, all metadata of
  objects in the form is preserved."
  ([f form] (walk-indexed [] f form))
  ([coor f form]
   (let [map-inner (fn [forms]
                     (map-indexed #(walk-indexed (conj coor %1) f %2)
                                  forms))
         ;; Maps are unordered, but we can try to use the keys (and
         ;; they're important enough that we're willing to risk
         ;; getting the position wrong).
         result (cond (map? form)  (into {} (map (fn [[k v]]
                                                   [k (walk-indexed (conj coor (pr-str k)) f v)])
                                                 form))
                      ;; Order of sets is unpredictable, unfortunately.
                      (set? form)  form
                      ;; Borrowed from clojure.walk/walk
                      (list? form) (apply list (map-inner form))
                      (instance? clojure.lang.IMapEntry form) (vec (map-inner form))
                      (seq? form)  (doall (map-inner form))
                      (coll? form) (into (empty form) (map-inner form))
                      :else form)]
     (f coor (with-meta-safe result (meta form))))))

(defn macroexpand-all
  "Like clojure.walk/macroexpand-all, but preserves and macroexpands
  metadata."
  [form]
  (let [md (meta form)
        expanded (walk/walk macroexpand-all
                            identity
                            (if (seq? form) (macroexpand form) form))]
    (if md
      ;; Macroexpand the metadata too, because sometimes metadata
      ;; contains, for example, functions. This is the case for
      ;; deftest forms.
      (with-meta-safe expanded (macroexpand-all md))
      expanded)))

(defn instrument-tagged-code
  "Return `form` instrumented with breakpoints.
  It is expected that something in `form` will contain a
  :cider-breakfunction metadata, whose value should be a function or
  macro. It should take two arguments, the form being evaluated, and a
  coordinates vector (see below).

  This function walks through the code attaching to objects
  the :cider-coor metadata, which is a vector specifing its position
  in the top-level form. As an example, a coordinate vector of [3 2 0]
  means:
    - enter this sexp and move forward three times,
    - enter this sexp and move forward twice,
    - enter this sexp.

  After that, it fully macroexpands the code, walks through it again,
  and wraps in a breakpoint any form that contains the previously
  attached metadata."
  [form]
  (->> form
       ;; Fill the form with metadata. This will later tell us which
       ;; of the final (post-expansion) forms correspond to user
       ;; code (and where it came from).
       (walk-indexed (fn [i f] (with-meta-safe f {:cider-coor i})))
       ;; Expand so we don't have to deal with macros.
       (macroexpand-all)
       ;; Go through everything again, and instrument any form with
       ;; debug metadata.
       (instrument)
       (#(do (when verbose-debug (println "[DBG]" %))
             %))))
