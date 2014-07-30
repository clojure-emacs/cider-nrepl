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
            ;; The var is refered to
            ((ns-refers ns) symb-name) symb-name
            ;; No alias and from a different namespace
            :else x))
         x))
     form)))

(defn macroexpansion [op code ns-name suppress-namespaces]
  ;; suppress-namespaces can either be
  ;;    "tidy" => print aliases instead of qnames, simple names if
  ;;              var is refered to or defined in the same ns
  ;;    falsy  => print qnames
  ;;    truthy => print simple names
  (let [;; FIXME: If cider-macroexpansion-suppress-namespaces is nil,
        ;; suppress-namespaces is [] which is truthy.  That's the cause of bug
        ;; https://github.com/clojure-emacs/cider/issues/684.  It's fixed
        ;; below, but there is probably a more general way.
        suppress-namespaces (if (coll? suppress-namespaces)
                              (seq suppress-namespaces)
                              suppress-namespaces)
        expansion-fn (resolve-op op)
        ns (find-ns (symbol ns-name))
        ;; we have to do the macroexpansion in the proper ns context
        expansion (binding [*ns* ns] (expansion-fn (read-string code)))
        [expansion suppress-namespaces] (if (= suppress-namespaces "tidy")
                                          [(tidy-qualified-var-refs expansion ns) false]
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
