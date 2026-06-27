(ns shadow-sample.node)

(defn greet [name]
  (str "Hello, " name "!"))

(defn main [& _args]
  (js/console.log (greet "world")))
