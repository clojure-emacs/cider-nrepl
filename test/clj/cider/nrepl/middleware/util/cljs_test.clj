(ns cider.nrepl.middleware.util.cljs-test
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [clojure.test :refer :all]))

;; reach the private provider internals
(def ^:private shadow-env-handle #'cljs/shadow-env-handle)
(def ^:private shadow-cljs-env #'cljs/shadow-cljs-env)
(def ^:private shadow-build-id #'cljs/shadow-build-id)

(deftest provider-chain-test
  (testing "first non-nil provider wins"
    (with-redefs [cljs/cljs-env-providers [(constantly nil)
                                           (constantly :env-b)
                                           (constantly :env-c)]]
      (is (= :env-b (cljs/grab-cljs-env* {})))))

  (testing "nil when no provider matches"
    (with-redefs [cljs/cljs-env-providers [(constantly nil) (constantly nil)]]
      (is (nil? (cljs/grab-cljs-env* {})))))

  (testing "grab-cljs-env derefs whatever the provider returns"
    (with-redefs [cljs/cljs-env-providers [(constantly (atom {:a 1}))]]
      (is (= {:a 1} (cljs/grab-cljs-env {}))))))

(deftest try-resolve-piggieback-degrades-on-error-test
  (testing "a class-version error while loading ClojureScript degrades to nil, not a crash"
    ;; Recent ClojureScript ships a Java-21 closure-compiler, so on an older JDK
    ;; the piggieback require surfaces an `UnsupportedClassVersionError` - an
    ;; Error, not an Exception. The startup probe must swallow it and fall back
    ;; to a Clojure-only setup rather than take the whole middleware stack down.
    (with-redefs [requiring-resolve
                  (fn [_] (throw (UnsupportedClassVersionError.
                                  "closure-compiler has been compiled by a more recent JDK")))]
      (is (nil? (cljs/try-resolve-piggieback)))
      (is (= #{:session} (cljs/maybe-add-piggieback #{:session}))))))

(deftest shadow-env-handle-test
  (testing "nil stays nil"
    (is (nil? (shadow-env-handle nil))))

  (testing "an existing atom/ref is used as-is"
    (let [a (atom {:k 1})]
      (is (identical? a (shadow-env-handle a)))))

  (testing "a snapshot map is wrapped so it derefs and tolerates swaps"
    (let [env {:cljs.analyzer/namespaces {'cljs.user {}}}
          handle (shadow-env-handle env)]
      (is (instance? clojure.lang.IDeref handle))
      (is (= env @handle))
      ;; the analyzer transiently swaps the compiler env during macroexpansion;
      ;; that must not blow up (the write is just ephemeral here)
      (is (= 1 (:scratch (swap! handle assoc :scratch 1)))))))

(deftest shadow-build-id-test
  ;; Fake shadow's *repl-state* var so the fallback's `resolve` finds it, without
  ;; pulling shadow-cljs onto the test classpath.
  (let [repl-state-var (intern (create-ns 'shadow.cljs.devtools.server.nrepl-impl)
                               '*repl-state*)]
    (try
      (testing "reads the build id shadow stamps onto the message"
        (is (= :app (shadow-build-id
                     {:shadow.cljs.devtools.server.nrepl-impl/build-id :app}))))

      (testing "falls back to shadow's *repl-state* session var"
        (is (= :app (shadow-build-id
                     {:session (atom {repl-state-var {:build-id :app}})}))))

      (testing "the message key wins over the session var"
        (is (= :from-msg (shadow-build-id
                          {:shadow.cljs.devtools.server.nrepl-impl/build-id :from-msg
                           :session (atom {repl-state-var {:build-id :from-session}})}))))

      (testing "nil when neither is present"
        (is (nil? (shadow-build-id {:session (atom {})}))))
      (finally
        (remove-ns 'shadow.cljs.devtools.server.nrepl-impl)))))

(deftest shadow-cljs-env-test
  ;; Fake shadow's api/compiler-env so the provider's `resolve` finds it, without
  ;; pulling shadow-cljs onto the test classpath.
  (let [env-map {:cljs.analyzer/namespaces {'cljs.user {}}}]
    (intern (create-ns 'shadow.cljs.devtools.api) 'compiler-env
            (fn [build-id] (when (= build-id :app) env-map)))
    (try
      (testing "no build id anywhere -> nil"
        (is (nil? (shadow-cljs-env {:session (atom {})}))))

      (testing "build id (from the message) with a live build -> deref-able env"
        (let [handle (shadow-cljs-env {:shadow.cljs.devtools.server.nrepl-impl/build-id :app})]
          (is (some? handle))
          (is (= env-map @handle))))

      (testing "build present but no compiled env yet -> nil"
        (is (nil? (shadow-cljs-env
                   {:shadow.cljs.devtools.server.nrepl-impl/build-id :not-built}))))
      (finally
        (remove-ns 'shadow.cljs.devtools.api)))))
