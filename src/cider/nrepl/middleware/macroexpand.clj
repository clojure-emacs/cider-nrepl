(ns cider.nrepl.middleware.macroexpand
  "Macroexpansion middleware."
  {:author "Bozhidar Batsov"}
  (:require [clojure.pprint :as pp]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]))

(defn- resolve-op [op]
  (let [sym (symbol op)]
    (if (= sym 'macroexpand-all)
     (ns-resolve 'clojure.walk sym)
     (resolve sym))))

(defn macroexpansion [op code suppress-namespaces]
  (let [expansion-fn (resolve-op op)
        expansion (expansion-fn (read-string code))]
    (with-out-str
      (pp/write expansion
                :suppress-namespaces suppress-namespaces
                :dispatch clojure.pprint/code-dispatch))))

(defn macroexpansion-reply
  [{:keys [transport op code suppress-namespaces] :as msg}]
  (transport/send transport (response-for msg :value (macroexpansion op code suppress-namespaces)))
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
