(ns cider.nrepl.middleware.refresh-test
  (:require
   [cider.nrepl.middleware.refresh :as r]
   [cider.nrepl.middleware.util.reload :as reload-utils]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]))

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
    (is (#'reload-utils/zero-arity-callable?
         (resolve (symbol "cider.nrepl.middleware.refresh-test" "before-fn"))))))

(deftest refresh-op-test
  (testing "refresh op works"
    (is+ {:reloading some?
          :status #{"done" "ok"}}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload})))

  (testing "nothing to refresh after refreshing"
    (is+ {:reloading []
          :status #{"done" "ok"}}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload}))))

(deftest before-fn-test
  (testing "before fn works"
    (is+ {:reloading some?
          :status #{"done" "invoked-before" "invoking-before" "ok"}
          :out "before-fn invoked\n"}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :before "cider.nrepl.middleware.refresh-test/before-fn"})))

  (testing "bad before fn results in not resolved response"
    (is+ {:status #{"done" "invoked-not-resolved" "ok" "invoking-before"}}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :before "non-existent/foo"}))

    (is+ {:status #{"done" "error" "invoking-before"}
          :err some?
          :error some?}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :before "clojure.core/seq"}))

    (is+ {:status #{"done" "invoked-not-resolved" "invoking-before" "ok"}}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :before "java.lang.Thread"}))))

(deftest after-fn-test
  (testing "after fn with zero arity works"
    (is+ {:reloading some?
          :status #{"done" "invoked-after" "invoking-after" "ok"}
          :out "after-fn invoked\n"}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :after "cider.nrepl.middleware.refresh-test/after-fn"})))

  (testing "after fn with optional arg works"
    (is+ {:reloading some?
          :status #{"done" "invoked-after" "invoking-after" "ok"}
          :out "after with optional argument works\n"}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :after "cider.nrepl.middleware.refresh-test/after-fn-optional-arg"})))

  (testing "bad after fn results in error"
    (is+ {:status #{"done" "invoked-not-resolved" "invoking-after" "ok"}}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :after "non-existent/foo"}))

    (is+ {:status #{"done" "error" "invoking-after" "ok"}
          :err some?
          :error some?}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :after "clojure.core/seq"}))

    (is+ {:status #{"done" "invoked-not-resolved" "invoking-after" "ok"}}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload
                           :after "java.lang.Thread"}))))

(deftest refresh-all-op-test
  (testing "refresh-all op works"
    (is+ {:reloading not-empty
          :status #{"done" "ok"}}
         (session/message {:op "cider/refresh-all"
                           :dirs dirs-to-reload}))))

(deftest refresh-clear-op-test
  (testing "refresh-clear op works"
    (is+ {:status #{"done"}}
         (do (session/message {:op "cider/refresh"
                               :dirs dirs-to-reload})
             (session/message {:op "cider/refresh-clear"}))))

  (testing "refresh op works after refresh clear"
    (is+ {:reloading not-empty
          :status #{"done" "ok"}}
         (session/message {:op "cider/refresh"
                           :dirs dirs-to-reload}))))

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

(deftest deprecated-ops-test
  (testing "Deprecated 'refresh' op still works"
    (is+ {:status (mc/embeds #{"done"})}
         (session/message {:op "refresh"
                           :dirs dirs-to-reload})))

  (testing "Deprecated 'refresh-all' op still works"
    (is+ {:reloading not-empty
          :status #{"done" "ok"}}
         (session/message {:op "refresh-all"
                           :dirs dirs-to-reload})))

  (testing "Deprecated 'refresh-clear' op still works"
    (is+ {:status #{"done"}}
         (session/message {:op "refresh-clear"}))))
