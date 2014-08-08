(ns cider.nrepl.middleware.macroexpand
  "Macroexpansion middleware."
  {:author "Bozhidar Batsov"}
  (:require [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn- resolve-op [op]
  (let [sym (symbol op)]
    (if (= sym 'macroexpand-all)
     (ns-resolve 'clojure.walk sym)
     (resolve sym))))

(defn- tidy-qualified-var-refs
  "Takes a `form` (usually being the result of a macroexpansion) and replaces
  any fully qualified var references with the namespace aliases defined for the
  namespace `ns`.  Var references to vars of the current namespace `ns` are
  replaced with simple names."
  [form ns]
  (let [alias2ns (ns-aliases ns)
        ns2alias (apply hash-map (mapcat (fn [[a n]]
                                           [(ns-name n) a])
                                         alias2ns))]
    (walk/prewalk
     (fn [x]
       (if (and (symbol? x) (namespace x))
         (let [symb-ns (symbol (namespace x))
               symb-name (symbol (name x))
               alias (ns2alias symb-ns)]
           (cond
            ;; That namespace has an alias
            alias (symbol (str alias "/" symb-name))
            ;; That var is defined in the current namespace
            (= symb-ns (ns-name ns)) symb-name
            ;; The var is referred to
            ((ns-refers ns) symb-name) symb-name
            ;; No alias and from a different namespace
            :else x))
         x))
     form)))

(defn macroexpansion [op code ns-name display-namespaces]
  ;; display-namespaces can either be
  ;;    "tidy"      => print aliases instead of qnames, simple names if
  ;;                  var is refered to or defined in the same ns
  ;;    "qualified" => print qnames
  ;;    "node"      => print simple names
  (let [suppress-namespaces (= display-namespaces "none")
        expansion-fn (resolve-op op)
        ns (find-ns (symbol ns-name))
        ;; we have to do the macroexpansion in the proper ns context
        expansion (binding [*ns* ns] (expansion-fn (read-string code)))
        ;; post-process expansions if display namespaces is "tidy"
        [expansion suppress-namespaces] (if (= display-namespaces "tidy")
                                          [(tidy-qualified-var-refs expansion ns) false]
                                          [expansion suppress-namespaces])
        suppress-namespaces (boolean suppress-namespaces)]
    (with-out-str
      (pp/write expansion
                :suppress-namespaces suppress-namespaces
                :dispatch clojure.pprint/code-dispatch))))

(defn macroexpansion-reply
  [{:keys [transport op code ns display-namespaces] :as msg}]
  (transport/send transport (response-for msg :value (macroexpansion op code ns display-namespaces)))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-macroexpand
  "Middleware that provides macroexpansion ops."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (contains? #{"macroexpand" "macroexpand-1" "macroexpand-all"} op)
      (macroexpansion-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-macroexpand
 {:handles
  {"macroexpand"
   {:doc "Produces macroexpansion using macroexpand"
    :returns {"status" "done"}}
   "macroexpand-1"
   {:doc "Produces macroexpansion using macroexpand-1"
    :returns {"status" "done"}}
   "macroexpand-all"
   {:doc "Produces macroexpansion using macroexpand-all"
    :returns {"status" "done"}}}})
