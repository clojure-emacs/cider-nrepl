(ns cider.nrepl.middleware.ns-test
  (:require
   [clojure.test :refer :all]
   [cider.nrepl.middleware.test-transport :refer [messages test-transport]]
   [cider.nrepl.middleware.ns :refer [ns-list ns-vars]]))

(deftest test-toogle-ns-list
  (is (= (count (all-ns)) (count (ns-list)))))

(deftest test-toogle-ns-vars
  (let [ns "clojure.core"]
    (is (= (count (ns-publics (symbol ns))) (count (ns-vars ns))))))
