(ns cider.nrepl.middleware.cljs-stacktrace-test
  (:require
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :once piggieback-fixture)

(deftest cljs-stacktrace-test
  (testing "no last error"
    (let [response (session/message {:op :stacktrace})]
      (is (= #{"no-error" "done"}
             (:status response)))))
  (testing "last error stacktrace"
    (let [response (do (session/message {:op :eval
                                         :code "(ffirst 1)"})
                       (session/message {:op :stacktrace}))]
      (is (= #{"done"}
             (:status response)))
      (is (= "clojure.lang.ExceptionInfo"
             (:class response))))))
