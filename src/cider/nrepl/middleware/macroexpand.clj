(ns cider.nrepl.middleware.macroexpand
  "Macroexpansion middleware."
  {:author "Bozhidar Batsov"}
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [cider.nrepl.middleware.util.misc :as u]
            [cljs-tooling.util.analysis :as cljs-ana]
            [clojure.pprint :as pp]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.reader :as reader]
            [clojure.walk :as walk])
  (:import [clojure.lang Var]))

;; Common helpers

(defn- var->namespace-qualified-name [^Var v]
  (symbol (str (.name (.ns v)))
          (str (.sym v))))

(defn- expandable?
  "Return true if form is macro-expandable."
  [form]
  (not= (macroexpand-1 form) form))

(defn macroexpand-step
  "Walk form, expanding the next subform."
  [form]
  (let [expanded? (atom false)]
    (walk/prewalk (fn [x]
                    (if (and (not @expanded?)
                             (expandable? x))
                      (do (reset! expanded? true)
                          (macroexpand-1 x))
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

;; Clojure impl

(defn- resolve-expander-clj
  "Returns the macroexpansion fn for macroexpanding Clojure code, corresponding
  to the given value of the :expander option."
  [expander]
  (case expander
    "macroexpand-1" macroexpand-1
    "macroexpand" macroexpand
    "macroexpand-all" walk/macroexpand-all
    "macroexpand-step" macroexpand-step
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
  [{:keys [display-namespaces ns] :as msg}]
  (case display-namespaces
    "qualified" identity
    "none" (fn [x]
             (cond-> x
               (and (symbol? x) (namespace x))
               (-> name symbol)))
    "tidy" (tidy-walker-clj msg)
    (throw (IllegalArgumentException. (format "Unrecognized value for display-namespaces: %s" display-namespaces)))))

(defn- expand-clj
  "Returns the macroexpansion of the given Clojure form :code, performed in the
  context of the given :ns, using the provided :expander and :display-namespaces
  options."
  [{:keys [code expander ns] :as msg}]
  (->> (let [expander-fn (resolve-expander-clj expander)]
         (binding [*ns* (find-ns ns)]
           (expander-fn (read-string code))))
       (walk/prewalk (post-expansion-walker-clj msg))))

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
  [{:keys [display-namespaces ns] :as msg}]
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
  in the context of the given :ns, using the provided :expander
  and :display-namespaces options."
  [{:keys [code expander ns] :as msg}]
  (->> (let [expander-fn (resolve-expander-cljs msg)]
         (cljs/with-cljs-env msg
           (cljs/with-cljs-ns ns
             (expander-fn (reader/read-string code)))))
       (walk/prewalk (post-expansion-walker-cljs msg))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn macroexpansion [{:keys [print-meta ns] :as msg}]
  (let [msg (merge {:expander "macroexpand" :display-namespaces "qualified"} msg)
        expansion (if (cljs/grab-cljs-env msg)
                    (expand-cljs (assoc msg :ns (or (u/as-sym ns) 'cljs.user)))
                    (expand-clj (assoc msg :ns (or (u/as-sym ns) 'user))))]
    (with-out-str
      (binding [*print-meta* (boolean print-meta)]
        (pp/write expansion :dispatch pp/code-dispatch)))))

(defn macroexpansion-reply [msg]
  {:expansion (macroexpansion msg)})

(defn wrap-macroexpand
  "Middleware that provides a macroexpand op."
  [handler]
  (with-safe-transport handler
    "macroexpand" [macroexpansion-reply :macroexpand-error]))

(set-descriptor!
 #'wrap-macroexpand
 (cljs/requires-piggieback
  {:handles
   {"macroexpand"
    {:doc "Produces macroexpansion of some form using the given expander."
     :requires {"code" "The form to macroexpand."}
     :optional {"ns" "The namespace in which to perform the macroexpansion. Defaults to 'user for Clojure and 'cljs.user for ClojureScript."
                "expander" "The macroexpansion function to use. Possible values are \"macroexpand-1\", \"macroexpand\", or \"macroexpand-all\". Defaults to \"macroexpand\"."
                "display-namespaces" "How to print namespace-qualified symbols in the result. Possible values are \"qualified\" to leave all namespaces qualified, \"none\" to elide all namespaces, or \"tidy\" to replace namespaces with their aliases in the given namespace. Defaults to \"qualified\"."
                "print-meta" "If truthy, also print metadata of forms."}
     :returns {"expansion" "The macroexpanded form."}}}}))
