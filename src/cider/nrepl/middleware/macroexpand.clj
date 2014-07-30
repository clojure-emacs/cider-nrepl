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

(defn- aliasify-qualified-var-refs
  "Takes a `form` (usually being the result of a macroexpansion) and replaces
  any fully qualified var references with the namespace aliases defined for the
  namespace `ns`."
  [form ns]
  (let [target-ns (the-ns ns)
        alias2ns (ns-aliases target-ns)
        ns2alias (apply hash-map (mapcat (fn [[a n]]
                                           [(ns-name n) a])
                                         alias2ns))]
    (walk/prewalk
     (fn [x]
       (if (and (symbol? x) (namespace x))
         (let [ns (symbol (namespace x))]
           (if-let [alias (ns2alias ns)]
             (symbol (str alias "/" (name x)))
             x))
         x))
     form)))

(defn macroexpansion [op code ns-name suppress-namespaces]
  ;; suppress-namespaces can either be
  ;;    'aliases => print aliases instead of qnames
  ;;    falsy    => print qnames
  ;;    truthy   => print simple names
  (let [expansion-fn (resolve-op op)
        ns (find-ns (symbol ns-name))
        ;; we have to do the macroexpansion in the proper ns context
        expansion (binding [*ns* ns] (expansion-fn (read-string code)))
        [expansion suppress-namespaces] (if (= suppress-namespaces 'aliases)
                                          [(aliasify-qualified-var-refs expansion ns) false]
                                          [expansion suppress-namespaces])
        suppress-namespaces (boolean suppress-namespaces)]
    (with-out-str
      (pp/write expansion
                :suppress-namespaces suppress-namespaces
                :dispatch clojure.pprint/code-dispatch))))

(defn macroexpansion-reply
  [{:keys [transport op code ns suppress-namespaces] :as msg}]
  (transport/send transport (response-for msg :value (macroexpansion op code ns suppress-namespaces)))
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
