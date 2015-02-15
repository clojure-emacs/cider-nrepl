(ns cider.nrepl.middleware.pprint-test
  (:require [cider.nrepl.middleware.pprint :refer [wrap-pprint]]
            [clojure.test :refer :all]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.server :as server]
            [clojure.tools.nrepl.transport :as transport]))

(defn send-message
  [message]
  (with-open [server (server/start-server :handler (server/default-handler #'wrap-pprint))
              transport (nrepl/connect :port (:port server))]
    (let [client (nrepl/client transport 1000)
          session (nrepl/client-session client)
          response (nrepl/message session message)]
      (nrepl/combine-responses response))))

(deftest test-wrap-pprint
  (let [code "[1 2 3 4 5 6 7 8 9 0]"]
    (testing "wrap-pprint does not interfere with normal :eval requests"
      (is (= [code]
             (:value (send-message {:op :eval
                                    :code code}))))
      (is (= nil
             (:out (send-message {:op :eval
                                  :code code})))))
    (testing "wrap-pprint does not clobber the :value slot"
      (is (= [code]
             (:value (send-message {:op :eval
                                    :code code
                                    :pprint "true"})))))
    (testing "wrap-pprint ensures that :eval requests are pretty-printed"
      (is (= "[1 2 3 4 5 6 7 8 9 0]\n"
             (:out (send-message {:op :eval
                                  :code code
                                  :pprint "true"})))))
    (testing "wrap-pprint respects the :right-margin slot"
      (is (= "[1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n 0]\n"
             (:out (send-message {:op :eval
                                  :code code
                                  :pprint "true"
                                  :right-margin 10})))))))
