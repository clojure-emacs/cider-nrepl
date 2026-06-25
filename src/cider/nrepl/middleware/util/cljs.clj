(ns cider.nrepl.middleware.util.cljs
  (:require
   [cider.nrepl.middleware.util :as util]))

(defn try-resolve-piggieback
  "If piggieback is loaded, return `#'cider.piggieback/wrap-cljs-repl`, nil
  otherwise."
  []
  (try (requiring-resolve 'cider.piggieback/wrap-cljs-repl)
       (catch Exception _)))

(defn expects-piggieback
  "Deprecated: returns the descriptor unchanged."
  {:deprecated "0.59.0"}
  [descriptor]
  descriptor)

(defn requires-piggieback
  "Deprecated: returns the descriptor unchanged."
  {:deprecated "0.59.0"}
  [descriptor]
  descriptor)

(defn maybe-add-piggieback
  "If piggieback is loaded, conj piggieback mw var to the provided collection."
  [coll]
  (if-some [pb (try-resolve-piggieback)]
    (conj coll pb)
    coll))

(defn- cljs-env-path
  "Returns the path in the session map for the ClojureScript compiler
  environment used by piggieback."
  []
  [(resolve 'cider.piggieback/*cljs-compiler-env*)])

(defn- maybe-deref
  [x]
  (if (instance? clojure.lang.IDeref x) @x x))

(defn grab-cljs-env*
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
    `(binding [cljs.env/*compiler* (grab-cljs-env* ~msg)]
       ~@body)
    (catch Exception _)))

(defmacro with-cljs-ns [ns-sym & body]
  (try
    (require 'cljs.analyzer)
    `(binding [cljs.analyzer/*cljs-ns* ~ns-sym]
       ~@body)
    (catch Exception _)))

(defn respond-clojure-only
  "Replies to `msg` signaling that its op is Clojure-only and isn't available
  while a ClojureScript REPL is active, then short-circuits the middleware. Lets
  the client surface a clear message instead of a confusing failure or a
  JVM-only result. See clojure-emacs/cider#2198."
  [{:keys [op] :as msg}]
  (util/respond-to msg
                   :status #{:done :clojure-only}
                   :err (str "The \"" op "\" op is Clojure-only and isn't available in a ClojureScript REPL.\n")))
