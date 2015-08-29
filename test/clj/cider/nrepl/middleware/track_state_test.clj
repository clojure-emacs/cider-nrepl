(ns cider.nrepl.middleware.track-state-test
  (:require [cider.nrepl.middleware.track-state :as s]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [clojure.test :refer :all]
            [clojure.tools.nrepl.transport :as t])
  (:import clojure.tools.nrepl.transport.Transport))

(deftest make-transport
  (is (instance? Transport (s/make-transport nil)))
  (is (try (send (s/make-transport nil) 10)
           nil
           (catch Exception e true))))

(deftest assoc-state
  (is (= (s/assoc-state {} {})
         {:state {:repl-type :clj}}))
  (with-redefs [cljs/grab-cljs-env identity]
    (is (= (s/assoc-state {} {})
           {:state {:repl-type :cljs}}))))
