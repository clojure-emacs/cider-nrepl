(ns cider.nrepl.middleware.util.spec)

(defn get-spec
  "Return the spec for var `v` if clojure.spec is available."
  [v]
  (when-let [f (resolve (symbol "clojure.spec" "get-spec"))]
    (f v)))

(defn describe
  "Describe the spec `s` if clojure.spec is available."
  [s]
  (when-let [f (resolve (symbol "clojure.spec" "describe"))]
    (f s)))
