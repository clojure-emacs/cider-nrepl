(ns cider.nrepl.middleware.macroexpand
  "Macroexpansion middleware."
  {:author "Bozhidar Batsov"}
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling
    :refer [base-error-response eval-interceptor-transport with-safe-transport]]
   [orchard.cljs.analysis :as cljs-ana]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [clojure.pprint :as pp]
   [clojure.tools.reader :as reader]
   [clojure.walk :as walk]
   [orchard.misc :as misc])
  (:import
   [clojure.lang Var]))

;; Macroexpansion for CLJ and CLJS follow two separate paths. In CLJ, a special
;; macroexpanding code is formatted and passed down as `eval` message, then the
;; result is intercepted, processed, and returned. In CLJS, the middleware
;; itself performs the expansion as there is no way to macroexpand the
;; expression with the evaluating part of CLJS.

;; Common helpers

(defn- var->namespace-qualified-name [^Var v]
  (symbol (str (.name (.ns v)))
          (str (.sym v))))

(defn macroexpand-step
  "Walk form, expanding the next subform."
  [form]
  (let [expanded? (atom false)]
    (walk/prewalk (fn [x]
                    (if-not @expanded?
                      (let [x' (macroexpand-1 x)]
                        (when (not= x x')
                          (reset! expanded? true))
                        x')
                      x))
                  form)))

(defn- tidy-namespaced-sym
  "Given a namespace-qualified symbol, returns the symbol to be printed when
  the :display-namespaces option is set to 'tidy.

  * sym is the namespace-qualified symbol to be tidied.
  * ns is the name of the namespace in which macroexpansion is being performed.
  * aliases is a map from namespace name to namespace alias.
  * refers is a map from non-namespace-qualified referred vars to their namespace-qualified names."
  [sym ns aliases refers]
  (let [ns-sym (symbol (namespace sym))
        name-sym (symbol (name sym))
        alias (get aliases ns-sym)]
    (cond
      ;; The namespace has an alias
      alias (symbol (str alias) (str name-sym))
      ;; The var is defined in the current namespace
      (= ns-sym ns) name-sym
      ;; The var is referred to
      (= sym (get refers name-sym)) name-sym
      ;; No alias and from a different namespace
      :else sym)))

(defn- tidy-walker
  "Returns a fn suitable for passing to clojure.walk/prewalk for processing a
  macroexpanded form according to the 'tidy value of the :display-namespaces
  option. See the docstring of tidy-namespaced-sym for the meaning of aliases
  and refers."
  [ns aliases refers]
  (fn [x]
    (cond-> x
      (and (symbol? x) (namespace x))
      (tidy-namespaced-sym ns aliases refers))))

(defn- macroexpansion-response-map
  [{:keys [print-meta] :as msg} expanded-form]
  (let [expansion (with-out-str
                    (binding [*print-meta* (boolean print-meta)]
                      (pp/write expanded-form :dispatch pp/code-dispatch)))]
    {:expansion expansion}))

;; Clojure impl

(defn- resolve-expander-clj
  "Returns the qualified macroexpansion fn symbol for macroexpanding Clojure code,
  corresponding to the given value of the :expander option."
  [expander]
  (case expander
    "macroexpand-1" `macroexpand-1
    "macroexpand" `macroexpand
    "macroexpand-all" `walk/macroexpand-all
    "macroexpand-step" `macroexpand-step
    (throw (IllegalArgumentException. (format "Unrecognized expander: %s" expander)))))

(defn- tidy-walker-clj
  "Returns a fn suitable for passing to clojure.walk/prewalk for processing a
  macroexpanded Clojure form according to the 'tidy value of
  the :display-namespaces option."
  [{:keys [ns]}]
  (let [aliases (->> (ns-aliases ns)
                     (into {} (map (fn [[a n]]
                                     [(ns-name n) a]))))
        refers (->> (ns-refers ns)
                    (into {} (map (fn [[r v]]
                                    [r (var->namespace-qualified-name v)]))))]
    (tidy-walker ns aliases refers)))

(defn- post-expansion-walker-clj
  "Returns a fn suitable for passing to clojure.walk/prewalk for processing a
  macroexpanded Clojure form according to the given value of
  the :display-namespaces option."
  [{:keys [display-namespaces] :as msg}]
  (case display-namespaces
    "qualified" identity
    "none" (fn [x]
             (cond-> x
               (and (symbol? x) (namespace x))
               (-> name symbol)))
    "tidy" (tidy-walker-clj msg)
    (throw (IllegalArgumentException. (format "Unrecognized value for display-namespaces: %s" display-namespaces)))))

(defn- send-middleware-error [msg ex]
  (transport/send (:transport msg)
                  (base-error-response msg ex :done :macroexpand-error)))

(defn macroexpansion-reply-clj [{:keys [transport] :as msg}
                                {:keys [value] :as resp}]
  (try (let [msg (update msg :ns #(or (misc/as-sym %) 'user))
             expansion (walk/prewalk (post-expansion-walker-clj msg) value)
             response-map (macroexpansion-response-map msg expansion)]
         (transport/send transport (response-for msg response-map)))
       (catch Exception ex
         (send-middleware-error msg ex))))

(defn handle-macroexpand-clj
  "Substitute the incoming `macroexpand` message with an `eval` message that will
  perform the expansion for us, intercept the result with
  `eval-interceptor-transport`, post-process it and return back to the client.

  Delegating the actual expansion (and even reading the code string) to `eval`
  op is preferable because it ensures that the context (state of dynamic
  variables) for macroexpansion is identical to if the user called `(macroexpand
  ...)` manually at the REPL."
  [handler {:keys [code expander ns] :as msg}]
  ;; `try` is not around the handler but only around constructing the msg
  ;; because we don't want to catch an error from some underlying middleware.
  (let [msg
        (try (let [expander-fn (resolve-expander-clj expander)
                   ;; Glueing strings may seem ugly, but this is the most
                   ;; reliable way to ensure the macroexpansion fully happens in
                   ;; a correct context.
                   expander-code (format "(%s '%s)" expander-fn code)
                   transport (eval-interceptor-transport
                              msg macroexpansion-reply-clj :macroexpand-error)]
               (assoc msg
                      :op "eval"
                      :code expander-code
                      :transport transport
                      ;; Make sure nrepl.middleware.print or alternatives don't
                      ;; stringify our value before it gets back to us.
                      :nrepl.middleware.print/keys []))
             (catch Exception ex
               (send-middleware-error msg ex)
               nil))]
    (some-> msg handler)))

;; ClojureScript impl

(def ^:private macroexpand-1-cljs
  (try
    (require 'cljs.analyzer)
    @(resolve 'cljs.analyzer/macroexpand-1)
    (catch Exception _)))

(defn- resolve-expander-cljs
  "Returns the macroexpansion fn for macroexpanding ClojureScript code,
  corresponding to the given value of the :expander option."
  [{:keys [expander] :as msg}]
  (let [macroexpand-1 (fn [form]
                        (macroexpand-1-cljs (cljs/grab-cljs-env msg) form))

        macroexpand (fn [form]
                      (let [mform (macroexpand-1 form)]
                        (cond-> mform
                          (not (identical? form mform)) (macroexpand))))

        macroexpand-all (fn [form]
                          (->> form
                               (walk/prewalk (fn [x]
                                               (cond-> x
                                                 (seq? x) (macroexpand))))))]
    (case expander
      "macroexpand-1" macroexpand-1
      "macroexpand" macroexpand
      "macroexpand-all" macroexpand-all
      (throw (IllegalArgumentException. (format "Unrecognized expander: %s" expander))))))

(defn- tidy-walker-cljs
  "Returns a fn suitable for passing to clojure.walk/prewalk for processing a
  macroexpanded ClojureScript form according to the 'tidy value of
  the :display-namespaces option."
  [{:keys [ns] :as msg}]
  (let [cljs-env (cljs/grab-cljs-env msg)

        aliases (->> (merge (cljs-ana/ns-aliases cljs-env ns)
                            (cljs-ana/macro-ns-aliases cljs-env ns))
                     (into {} (keep (fn [[a n]]
                                      ;; CLJS compiler includes entries like
                                      ;; '[clojure.set clojure.set]
                                      (when-not (= a n)
                                        [n a])))))

        refers (merge (cljs-ana/referred-vars cljs-env ns)
                      (cljs-ana/referred-macros cljs-env ns)
                      ;; CLJS doesn't have the equivalent of (ns-refers ns)
                      ;; which includes everything from cljs.core
                      (->> (cljs-ana/core-vars cljs-env ns)
                           (into {} (map (fn [[r {qualified-name :name}]]
                                           [r (symbol qualified-name)]))))
                      (->> (cljs-ana/core-macros cljs-env ns)
                           (into {} (map (fn [[r v]]
                                           [r (var->namespace-qualified-name v)])))))]
    (fn [x]
      (cond-> x
        (and (symbol? x) (namespace x))
        (tidy-namespaced-sym ns aliases refers)))))

(defn- post-expansion-walker-cljs
  "Returns a fn suitable for passing to clojure.walk/prewalk for processing a
  macroexpanded ClojureScript form according to the given value of
  the :display-namespaces option."
  [{:keys [display-namespaces] :as msg}]
  (case display-namespaces
    "qualified" identity
    "none" (fn [x]
             (cond-> x
               (and (symbol? x) (namespace x)
                    ;; js is a special namespace in CLJS; we don't want to remove it
                    (not= "js" (namespace x)))
               (-> name symbol)))
    "tidy" (tidy-walker-cljs msg)
    (throw (IllegalArgumentException. (format "Unrecognized value for display-namespaces: %s" display-namespaces)))))

(defn- expand-cljs
  "Returns the macroexpansion of the given ClojureScript form :code, performed
  in the context of the given :ns, using the provided :expander.
  and :display-namespaces options."
  [{:keys [code ns] :as msg}]
  (let [expander-fn (resolve-expander-cljs msg)
        code (reader/read-string code)]
    (walk/prewalk (post-expansion-walker-cljs msg)
                  (cljs/with-cljs-env msg
                    (cljs/with-cljs-ns ns
                      (expander-fn code))))))

(defn macroexpansion-reply-cljs [msg]
  (let [msg (update msg :ns #(or (misc/as-sym %) 'cljs.user))]
    (macroexpansion-response-map msg (expand-cljs msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handle-macroexpand* [handler msg]
  (let [msg (merge {:expander "macroexpand" :display-namespaces "qualified"} msg)]
    (if (cljs/grab-cljs-env msg)
      (with-safe-transport handler msg
        "macroexpand" [macroexpansion-reply-cljs :macroexpand-error])
      (handle-macroexpand-clj handler msg))))

(defn handle-macroexpand [handler msg]
  (if (= (:op msg) "macroexpand")
    (handle-macroexpand* handler msg)
    (handler msg)))
