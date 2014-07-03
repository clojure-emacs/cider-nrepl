(ns cider.nrepl.middleware.macroexpand-test
  (:use clojure.test
        cider.nrepl.middleware.test-transport
        cider.nrepl.middleware.macroexpand))

(def code
  {:expr         "(while (while 1))"
   :expanded     "(loop* [] (when (while 1) (recur)))"
   :expanded-1   "(loop [] (when (while 1) (recur)))"
   :expanded-all "(loop* [] (if (loop* [] (if 1 (do (recur)))) (do (recur))))"})

(deftest test-macroexpand-1-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-1"
                           :code (:expr code)
                           :suppress-namespaces true})
    (is (= (messages transport)
           [{:value (:expanded-1 code)} {:status #{:done}}]))))

(deftest test-macroexpand-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand"
                           :code (:expr code)
                           :suppress-namespaces true})
    (is (= (messages transport)
           [{:value (:expanded code)} {:status #{:done}}]))))

(deftest test-macroexpand-all-op
  (let [transport (test-transport)]
    (macroexpansion-reply {:transport transport
                           :op "macroexpand-all"
                           :code (:expr code)
                           :suppress-namespaces true})
    (is (= (messages transport)
           [{:value (:expanded-all code)} {:status #{:done}}]))))
