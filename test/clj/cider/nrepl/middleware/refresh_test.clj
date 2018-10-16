(ns cider.nrepl.middleware.refresh-test
  (:require
   [cider.nrepl.middleware.refresh :as r]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(def ^:private dirs-to-reload
  ;; Limit the scope of what we reload, because (for example) reloading the
  ;; cider.nrepl.middleware.test-session ns causes *session* in that ns to be
  ;; unloaded, which breaks session-fixture, and hence all of the below tests.
  ["test/clj/cider/nrepl/middleware/util"])

(defn before-fn []
  (println "before-fn invoked"))

(defn- after-fn []
  (println "after-fn invoked"))

(defn- after-fn-optional-arg [& a]
  (when a (throw (IllegalArgumentException. "should not have been called with arg")))
  (println "after with optional argument works"))

(deftest invoking-function-tests
  (testing "invoking named function works"
    (is (#'r/zero-arity-callable?
         (resolve (symbol "cider.nrepl.middleware.refresh-test" "before-fn"))))))

(deftest refresh-op-test
  (testing "refresh op works"
    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload})]
      (is (:reloading response))
      (is (= #{"done" "ok"} (:status response)))))

  (testing "nothing to refresh after refreshing"
    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload})]
      (is (= [] (:reloading response)))
      (is (= #{"done" "ok"} (:status response))))))

(deftest before-fn-test
  (testing "before fn works"
    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :before "cider.nrepl.middleware.refresh-test/before-fn"})]
      (is (:reloading response))
      (is (= #{"done" "invoked-before" "invoking-before" "ok"} (:status response)))
      (is (= "before-fn invoked\n" (:out response)))))

  (testing "bad before fn results in not resolved response"
    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :before "foo"})]
      (is (= #{"done" "invoked-not-resolved" "ok" "invoking-before"} (:status response))))

    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :before "clojure.core/seq"})]
      (is (= #{"done" "error" "invoking-before"} (:status response)))
      (is (:err response))
      (is (:error response)))

    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :before "java.lang.Thread"})]
      (is (= #{"done" "invoked-not-resolved" "invoking-before" "ok"}
             (:status response))))))

(deftest after-fn-test
  (testing "after fn with zero arity works"
    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :after "cider.nrepl.middleware.refresh-test/after-fn"})]
      (is (:reloading response))
      (is (= #{"done" "invoked-after" "invoking-after" "ok"} (:status response)))
      (is (= "after-fn invoked\n" (:out response)))))

  (testing "after fn with optional arg works"
    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :after "cider.nrepl.middleware.refresh-test/after-fn-optional-arg"})]
      (is (:reloading response))
      (is (= #{"done" "invoked-after" "invoking-after" "ok"} (:status response)))
      (is (= "after with optional argument works\n" (:out response)))))

  (testing "bad after fn results in error"
    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :after "foo"})]
      (is (= #{"done" "invoked-not-resolved" "invoking-after" "ok"} (:status response))))

    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :after "clojure.core/seq"})]
      (is (= #{"done" "error" "invoking-after" "ok"} (:status response)))
      (is (:error response))
      (is (:err response)))

    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload
                                     :after "java.lang.Thread"})]
      (is (= #{"done" "invoked-not-resolved" "invoking-after" "ok"} (:status response))))))

(deftest refresh-all-op-test
  (testing "refresh-all op works"
    (let [response (session/message {:op "refresh-all"
                                     :dirs dirs-to-reload})]
      (is (seq (:reloading response)))
      (is (= #{"done" "ok"} (:status response))))))

(deftest refresh-clear-op-test
  (testing "refresh-clear op works"
    (let [_ (session/message {:op "refresh"
                              :dirs dirs-to-reload})
          response (session/message {:op "refresh-clear"})]
      (is (= #{"done"} (:status response)))))

  (testing "refresh op works after refresh clear"
    (let [response (session/message {:op "refresh"
                                     :dirs dirs-to-reload})]
      (is (seq (:reloading response)))
      (is (= #{"done" "ok"} (:status response))))))

(deftest user-refresh-dirs-test
  (testing "returns nil if clojure.tools.namespace isn't loaded"
    (with-redefs [resolve (constantly nil)]
      (is (nil? (#'r/user-refresh-dirs)))))

  ;; Disabling the next test.
  ;; Unclear how to get the "real" clojure.tools.namespace.repl in
  ;; this test when this project also localizes via mranderson.
  #_(testing "honors set-refresh-dirs"
      (c.t.n.r/set-refresh-dirs "foo" "bar")
      (is (= ["foo" "bar"] (#'r/user-refresh-dirs)))))

(deftest load-disabled-test
  (testing "is false by default"
    (let [ns-name     (gensym "test")
          ns-obj      (create-ns ns-name)]
      (is (false? (#'r/load-disabled? ns-name)))))

  (testing "is true when :c.t.n.r/load false"
    (let [ns-name     (gensym "test")
          ns-obj      (create-ns ns-name)]
      (alter-meta! ns-obj assoc :clojure.tools.namespace.repl/load false)
      (is (true? (#'r/load-disabled? ns-name))))))

(deftest unload-disabled-test
  (testing "is false by default"
    (let [ns-name     (gensym "test")
          ns-obj      (create-ns ns-name)]
      (is (false? (#'r/unload-disabled? ns-name)))))

  (testing "is true when :c.t.n.r/unload false"
    (let [ns-name     (gensym "test")
          ns-obj      (create-ns ns-name)]
      (alter-meta! ns-obj assoc :clojure.tools.namespace.repl/unload false)
      (is (true? (#'r/unload-disabled? ns-name)))))

  (testing "is true when :c.t.n.r/load false (implied)"
    (let [ns-name     (gensym "test")
          ns-obj      (create-ns ns-name)]
      (alter-meta! ns-obj assoc :clojure.tools.namespace.repl/load false)
      (is (true? (#'r/unload-disabled? ns-name))))))
