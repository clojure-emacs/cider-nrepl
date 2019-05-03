(ns cider.nrepl.middleware.xref
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [clojure.java.io :as io]
   [orchard.xref :as xref]
   [orchard.misc :as u]))

(defn- var->str [var]
  (subs (str var) 2))

(defn fn-refs-reply [{:keys [ns symbol]}]
  (let [var (ns-resolve (u/as-sym ns) (u/as-sym symbol))]
   {:fn-refs (map var->str (xref/fn-refs var))}))

(defn fn-deps-reply [{:keys [ns symbol]}]
  (let [var (ns-resolve (u/as-sym ns) (u/as-sym symbol))]
    {:fn-deps (map var->str (xref/fn-deps var))}))

(defn handle-xref [handler msg]
  (with-safe-transport handler msg
    "fn-refs" fn-refs-reply
    "fn-deps" fn-deps-reply))
