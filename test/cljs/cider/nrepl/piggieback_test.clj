(ns cider.nrepl.piggieback-test
  (:require
   [cider.piggieback :as piggieback]
   [cider.nrepl.test-session :as session]
   [cider.nrepl.middleware :refer [cider-middleware]]
   [cljs.repl.node :as node]
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.server :as server]))

(defn- start-cljs-repl!
  "Start a ClojureScript REPL in the current test session, on a fresh node
  repl-env. Fresh because a node repl-env can't be set up again once it has
  been torn down (its socket stays closed), and every `:cljs/quit` tears the
  runtime down. Fails loudly, so a broken REPL doesn't just leave the tests
  running against a Clojure session."
  []
  (let [response (session/message
                  {:op "eval"
                   :code (nrepl/code (require '[cider.piggieback :as piggieback]
                                              '[cljs.repl.node :as node])
                                     (piggieback/cljs-repl (node/repl-env)))})]
    (when-not (= #{"done"} (:status response))
      (throw (ex-info "Failed to start the ClojureScript REPL" response)))))

(def piggieback-fixture
  (compose-fixtures
   session/session-fixture
   (fn [f]
     (binding [session/*handler* (apply server/default-handler
                                        (conj (map resolve cider-middleware)
                                              #'piggieback/wrap-cljs-repl))]
       (start-cljs-repl!)
       (dorun (session/message {:op "eval"
                                :code (nrepl/code (require 'clojure.data))}))
       (f)
       (session/message {:op "eval"
                         :code (nrepl/code :cljs/quit)})))))

(use-fixtures :each piggieback-fixture)

(deftest sanity-test
  (testing "cljs repl is active"
    (let [response (session/message {:op "eval"
                                     :code (nrepl/code (js/Object.))})]
      (is (= "cljs.user" (:ns response)))
      ;; piggieback 0.6.2 fixed tagged-literal printing to emit the space after
      ;; the reader tag (nrepl/piggieback#120), so this is now "#js {}".
      (is (= ["#js {}"] (:value response)))
      (is (= #{"done"} (:status response)))))

  (testing "eval works"
    (let [response (session/message {:op "eval"
                                     :code (nrepl/code (map even? (range 6)))})]
      (is (= "cljs.user" (:ns response)))
      (is (= ["(true false true false true false)"] (:value response)))
      (is (= #{"done"} (:status response)))))

  (testing "errors handled properly"
    ;; `(ffirst 1)` is a deliberate type error - the point is to exercise
    ;; error handling - so silence the type-mismatch linter here.
    (let [response (session/message {:op "eval"
                                     :code #_{:clj-kondo/ignore [:type-mismatch]} (nrepl/code (ffirst 1))})]
      (is (= "class clojure.lang.ExceptionInfo"
             (:ex response)
             (:root-ex response)))
      (is (= #{"eval-error" "done"} (:status response))))))
