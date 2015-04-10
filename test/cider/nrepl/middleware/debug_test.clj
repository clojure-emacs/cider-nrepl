(ns cider.nrepl.middleware.debug-test
  (:require [clojure.test :refer :all]
            [cider.nrepl.middleware.debug :as d]))

(deftest breakpoint
  (binding [clojure.tools.nrepl.middleware.interruptible-eval/*msg*
            {:sync true}]
    (is (= (d/breakpoint 10 {}) 10))))

