(ns cider.nrepl.middleware.cljs-inspect-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :refer [message]]
            [cider.nrepl.middleware.util.inspect :as inspect]
            [clojure.test :refer :all]))

(use-fixtures :each piggieback-fixture)

(deftest cljs-inspect
  (let [response (message {:op "eval"
                           :inspect "true"
                           :code "[0 1 2]"})
        rendered (-> (inspect/fresh)
                     (inspect/start [0 1 2])
                     :rendered
                     pr-str)]
    (is (= [rendered] (:value response)))
    (is (= #{"done"} (:status response)))))

