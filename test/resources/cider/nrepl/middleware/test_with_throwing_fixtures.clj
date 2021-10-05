(ns cider.nrepl.middleware.test-with-throwing-fixtures
  (:require
   [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [t]
                      (throw (ex-info "I'm an exception inside a fixture!" {:data 42}))
                      (t)))

(deftest foo
  (is (= 42 (* 21 2))))
