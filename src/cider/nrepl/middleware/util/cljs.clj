(ns cider.nrepl.middleware.util.cljs)

;; piggieback 0.3 changed the namespace of the library, so in
;; order to support 0.2 and 0.3 we need to do a bit of extra work
(def cider-piggieback?
  (try (require 'cider.piggieback) true
       (catch Throwable _ false)))

(def cemerick-piggieback?
  (try (require 'cemerick.piggieback) true
       (catch Throwable _ false)))

(defn try-piggieback
  "If piggieback is loaded, returns `#'cider.piggieback/wrap-cljs-repl`, or
  false otherwise."
  []
  (cond
    cider-piggieback? (resolve 'cider.piggieback/wrap-cljs-repl)
    cemerick-piggieback? (resolve 'cemerick.piggieback/wrap-cljs-repl)
    :else false))

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
  [(if cider-piggieback?
     (resolve 'cider.piggieback/*cljs-compiler-env*)
     (resolve 'cemerick.piggieback/*cljs-compiler-env*))])

(defn- maybe-deref
  [x]
  (if (instance? clojure.lang.IDeref x) @x x))

(defn- grab-cljs-env*
  [msg]
  (let [path (cljs-env-path)]
    (some-> msg
            :session
            maybe-deref
            (get-in path))))

(defn grab-cljs-env
  "If piggieback is active, returns the ClojureScript compiler environment for
  the running REPL."
  [msg]
  (maybe-deref (grab-cljs-env* msg)))

(defn cljs-response-value
  "Returns the :value slot of an eval response from piggieback as a Clojure
  value."
  [response]
  (let [value (:value response)]
    (try
      (read-string value)
      (catch Exception _
        value))))

(defn response-value
  "Returns the :value slot of an eval response as a Clojure value, reading the
  slot if necessary (piggieback 0.2.x)."
  [msg response]
  (if (grab-cljs-env msg)
    (cljs-response-value response)
    (:value response)))

(defmacro with-cljs-env [msg & body]
  (try
    (require 'cljs.env)
    `(binding [cljs.env/*compiler* ~(grab-cljs-env* msg)]
       ~@body)
    (catch Exception _)))

(defmacro with-cljs-ns [ns-sym & body]
  (try
    (require 'cljs.analyzer)
    `(binding [cljs.analyzer/*cljs-ns* ~ns-sym]
       ~@body)
    (catch Exception _)))
