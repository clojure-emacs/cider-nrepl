(ns cider.nrepl.piggieback-test
  (:require [cemerick.piggieback :as piggieback]
            [cider.nrepl.test-session :as session]
            [cider.nrepl :refer [cider-middleware]]
            [clojure.test :refer :all]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.server :as server]))

(def piggieback-fixture
  (compose-fixtures
   session/session-fixture
   (fn [f]
     (binding [session/*handler* (apply server/default-handler
                                        (conj (map resolve cider-middleware)
                                              #'piggieback/wrap-cljs-repl))]
       (session/message {:op :eval
                         :code (nrepl/code (require '[cemerick.piggieback :as piggieback])
                                           (require '[cljs.repl.node :as node])
                                           (piggieback/cljs-repl (node/repl-env)))})
       (session/message {:op :eval
                         :code (nrepl/code (require 'clojure.data))})
       (f)
       (session/message {:op :eval
                         :code (nrepl/code :cljs/quit)})))))

(use-fixtures :each piggieback-fixture)

(deftest sanity-test
  (testing "cljs repl is active"
    (let [response (session/message {:op :eval
                                     :code (nrepl/code (js/Object.))})]
      (is (= "cljs.user" (:ns response)))
      (is (= ["#js {}"] (:value response)))
      (is (= #{"done"} (:status response)))))

  (testing "eval works"
    (let [response (session/message {:op :eval
                                     :code (nrepl/code (map even? (range 6)))})]
      (is (= "cljs.user" (:ns response)))
      (is (= ["(true false true false true false)"] (:value response)))
      (is (= #{"done"} (:status response)))))

  (testing "errors handled properly"
    (let [response (session/message {:op :eval
                                     :code (nrepl/code (ffirst 1))})]
      (is (= "class clojure.lang.ExceptionInfo"
             (:ex response)
             (:root-ex response)))
      (is (string? (:err response)))
      (is (= #{"eval-error" "done"} (:status response))))))
