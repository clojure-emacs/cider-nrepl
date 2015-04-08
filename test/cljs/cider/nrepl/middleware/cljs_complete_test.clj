(ns cider.nrepl.middleware.cljs-complete-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each piggieback-fixture)

(deftest cljs-complete
  (let [response (session/message {:op "complete"
                                   :ns "cljs.user"
                                   :symbol ""})]
    (is (= #{"done"} (:status response)))
    (is (sequential? (:completions response)))
    (is (every? map? (:completions response))))

  (let [response (session/message {:op "complete"
                                   :ns "cljs.user"
                                   :symbol "defpro"})
        candidate (first (:completions response))]
    (is (= "defprotocol" (:candidate candidate)))
    (is (= "cljs.core" (:ns candidate)))
    (is (= "macro" (:type candidate)))))
