(ns cider.nrepl.middleware.debug-test
  (:require [clojure.test :refer :all]
            [debugger.time :as t]
            [debugger.config :as c]
            [cider.nrepl.middleware.debug :as d]))

(deftest breakpoint
  (reset! c/*last-quit-at* (t/now))
  (is (= (d/breakpoint 10 {}) 10)))

