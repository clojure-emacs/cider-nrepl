(ns example)

(defmacro foo []
  (println (class @clojure.lang.Compiler/LOADER))
  (assert (not (instance? clojure.lang.Var$Unbound @clojure.lang.Compiler/LOADER)))
  42)
