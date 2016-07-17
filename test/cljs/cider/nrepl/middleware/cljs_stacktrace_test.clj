(ns cider.nrepl.middleware.cljs-stacktrace-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each piggieback-fixture)

(deftest cljs-stacktrace-test
  (testing "stacktrace op is not implemented"
    (let [response-with-no-error (session/message {:op :stacktrace})
          response-with-error (do (session/message {:op :eval
                                                    :code "(ffirst 1)"})
                                  (session/message {:op :stacktrace}))]
      (is (= #{"no-error" "done"}
             (:status response-with-no-error)
             (:status response-with-error))))))
