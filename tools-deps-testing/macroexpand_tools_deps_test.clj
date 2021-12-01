(ns macroexpand-tools-deps-test
  (:require
   [example]
   [cider.nrepl.middleware.macroexpand]))

(defn -main [& _]
  (println (cider.nrepl.middleware.macroexpand/macroexpansion {:ns 'example
                                                               :code "(foo)"}))
  (System/exit 0))
