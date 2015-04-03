(ns cider.nrepl.middleware.util.cljs)

(defn try-piggieback
  "If piggieback is loaded, returns `#'cemerick.piggieback/wrap-cljs-repl`, or
  false otherwise."
  []
  (try
    (require 'cemerick.piggieback)
    (resolve 'cemerick.piggieback/wrap-cljs-repl)
    (catch Exception _)))

;; TODO: warn on usage of piggieback 0.1.x?
(defn piggieback-0-2+?
  "Returns true if piggieback 0.2.x is loaded, or false otherwise."
  []
  (boolean
   (try
     (require 'cemerick.piggieback)
     (resolve 'cemerick.piggieback/*cljs-compiler-env*)
     (catch Exception _))))

(defn- maybe-piggieback
  [descriptor descriptor-key]
  (if-let [piggieback (try-piggieback)]
    (update-in descriptor [descriptor-key] #(set (conj % piggieback)))
    descriptor))

(defn expects-piggieback
  "If piggieback is loaded, returns the descriptor with piggieback's
  `wrap-cljs-repl` handler assoc'd into its `:expects` set."
  [descriptor]
  (maybe-piggieback descriptor :expects))

(defn requires-piggieback
  "If piggieback is loaded, returns the descriptor with piggieback's
  `wrap-cljs-repl` handler assoc'd into its `:requires` set."
  [descriptor]
  (maybe-piggieback descriptor :requires))

(defn- cljs-env-path
  "Returns the path in the session map for the ClojureScript compiler
  environment used by piggieback."
  []
  (if (piggieback-0-2+?)
    [(resolve 'cemerick.piggieback/*cljs-compiler-env*)]
    [(resolve 'cemerick.piggieback/*cljs-repl-env*) :cljs.env/compiler]))

(defn- maybe-deref
  [x]
  (if (instance? clojure.lang.IDeref x) @x x))

(defn grab-cljs-env
  "If piggieback is active, returns the ClojureScript compiler environment for
  the running REPL."
  [msg]
  (let [path (cljs-env-path)]
    (some-> msg
            :session
            maybe-deref
            (get-in path)
            maybe-deref)))

(defn cljs-response-value
  "Returns the :value slot of an eval response from piggieback as a Clojure
  value."
  [response]
  ;; Older versions of piggieback would attempt to read-string the result of
  ;; `cljs.repl/evaluate-form` and return that as the value, but newer versions
  ;; just return the output printed by `cljs.repl/repl*`.
  (let [value (:value response)]
    (if (and (string? value) (piggieback-0-2+?))
      (try
        (read-string value)
        (catch Exception _
          value))
      value)))

(defn response-value
  "Returns the :value slot of an eval response as a Clojure value, reading the
  slot if necessary (piggieback 0.2.x)."
  [msg response]
  (if (grab-cljs-env msg)
    (cljs-response-value response)
    (:value response)))
