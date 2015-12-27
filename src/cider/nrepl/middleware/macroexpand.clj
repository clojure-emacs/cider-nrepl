(ns cider.nrepl.middleware.macroexpand
  "Macroexpansion middleware."
  {:author "Bozhidar Batsov"}
  (:require [cider.nrepl.middleware.util.misc :as u]
            [clojure.pprint :as pp]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.walk :as walk])
  (:import [clojure.lang Var]))

(defn- var->namespace-qualified-name [^Var v]
  (symbol (str (.name (.ns v)))
          (str (.sym v))))

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

(defn- resolve-expander
  "Returns the macroexpansion fn for macroexpanding Clojure code, corresponding
  to the given value of the :expander option."
  [expander]
  (case expander
    "macroexpand-1" macroexpand-1
    "macroexpand" macroexpand
    "macroexpand-all" walk/macroexpand-all
    (throw (IllegalArgumentException. (format "Unrecognized expander: %s" expander)))))

(defn- tidy-walker
  "Returns a fn suitable for passing to clojure.walk/prewalk for processing a
  macroexpanded form according to the 'tidy value of the :display-namespaces
  option. See the docstring of tidy-namespaced-sym for the meaning of aliases
  and refers."
  [{:keys [ns]}]
  (let [aliases (->> (ns-aliases ns)
                     (map (fn [[a n]]
                            [(ns-name n) a]))
                     (into {}))
        refers (->> (ns-refers ns)
                    (map (fn [[r v]]
                           [r (var->namespace-qualified-name v)]))
                    (into {}))]
    (fn [x]
      (cond-> x
        (and (symbol? x) (namespace x))
        (tidy-namespaced-sym ns aliases refers)))))

(defn- post-expansion-walker
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
    "tidy" (tidy-walker msg)
    (throw (IllegalArgumentException. (format "Unrecognized value for display-namespaces: %s" display-namespaces)))))

(defn- expand
  "Returns the macroexpansion of the given Clojure form :code, performed in the
  context of the given :ns, using the provided :expander and :display-namespaces
  options."
  [{:keys [code expander ns] :as msg}]
  (->> (let [expander-fn (resolve-expander expander)]
         (binding [*ns* (find-ns ns)]
           (expander-fn (read-string code))))
       (walk/prewalk (post-expansion-walker msg))))

(defn macroexpansion [{:keys [print-meta ns] :as msg}]
  (let [msg (merge {:expander "macroexpand" :display-namespaces "qualified"} msg)
        expansion (expand (assoc msg :ns (or (u/as-sym ns) 'user)))]
    (with-out-str
      (binding [*print-meta* (boolean print-meta)]
        (pp/write expansion :dispatch pp/code-dispatch)))))

(defn macroexpansion-reply
  [{:keys [transport] :as msg}]
  (try
    (transport/send
     transport
     (response-for msg {:expansion (macroexpansion msg)
                        :status :done}))
    (catch Exception e
      (transport/send
       transport
       (response-for msg (u/err-info e :macroexpand-error))))))

(defn wrap-macroexpand
  "Middleware that provides a macroexpand op."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= op "macroexpand")
      (macroexpansion-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-macroexpand
 {:handles
  {"macroexpand"
   {:doc "Produces macroexpansion of some form using the given expander."
    :requires {"code" "The form to macroexpand."}
    :optional {"ns" "The namespace in which to perform the macroexpansion. Defaults to 'user for Clojure and 'cljs.user for ClojureScript."
               "expander" "The macroexpansion function to use. Possible values are \"macroexpand-1\", \"macroexpand\", or \"macroexpand-all\". Defaults to \"macroexpand\"."
               "display-namespaces" "How to print namespace-qualified symbols in the result. Possible values are \"qualified\" to leave all namespaces qualified, \"none\" to elide all namespaces, or \"tidy\" to replace namespaces with their aliases in the given namespace. Defaults to \"qualified\"."
               "print-meta" "If truthy, also print metadata of forms."}
    :returns {"expansion" "The macroexpanded form."}}}})
