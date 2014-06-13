(ns cider.nrepl.middleware.macroexpand-test
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.macroexpand))

(deftest test-macroexpand-1-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-1"
                           :code "(-> x f g)"
                           :suppress-namespaces true})
    (is (= (messages transport) [{:value "(-> (-> x f) g)"} {:status #{:done}}]))))

(deftest test-macroexpand-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :code "(-> x f g)"
                           :suppress-namespaces true})
    (is (= (messages transport) [{:value "(g (-> x f))"} {:status #{:done}}]))))

(deftest test-macroexpand-all-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-all"
                           :code "(-> x f g)"
                           :suppress-namespaces true})
    (is (= (messages transport) [{:value "(g (f x))"} {:status #{:done}}]))))
