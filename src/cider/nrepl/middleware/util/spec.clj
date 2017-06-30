(ns cider.nrepl.middleware.util.spec)


(defmacro spec [fname & args]
  `(when-let [f# (or (resolve (symbol "clojure.spec.alpha" ~fname))
                     (resolve (symbol "clojure.spec" ~fname)))]
     (f# ~@args)))

(defmacro spec-gen [fname & args]
  `(when-let [f# (or (resolve (symbol "clojure.spec.gen.alpha" ~fname))
                     (resolve (symbol "clojure.spec.gen" ~fname)))]
     (f# ~@args)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; This are all wrappers of clojure.spec.[alpha] functions.         ;;
;; We can't simply require the ns because it's existence depends on ;;
;; clojure version                                                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn get-spec [v] (spec "get-spec" v))

(defn describe [s] (spec "describe" s))

(defn registry [] (spec "registry"))

(defn form [s] (spec "form" s))

(defn generate [s] (spec-gen "generate" (spec "gen" s)))
