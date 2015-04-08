(ns cider.nrepl.middleware.cljs-ns-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each piggieback-fixture)

(deftest cljs-ns
  (let [ns-list (:ns-list (session/message {:op "ns-list"}))]
    (is (sequential? ns-list))
    (is (every? string? ns-list)))

  (let [ns-vars (:ns-vars (session/message {:op "ns-vars"
                                            :ns "cljs.reader"}))]
    (is (sequential? ns-vars))
    (is (every? string? ns-vars))))
