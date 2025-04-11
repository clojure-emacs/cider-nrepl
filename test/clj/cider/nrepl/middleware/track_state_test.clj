(ns cider.nrepl.middleware.track-state-test
  (:require
   [cider.nrepl.middleware.track-state :as sut]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.test :refer [deftest is testing]]
   [matcher-combinators.matchers :as matchers]
   [nrepl.core :as nrepl]
   [nrepl.misc]))

(def some-ns-map
  (delay {'cider.nrepl.middleware.track-state-test
          (with-bindings* {#'sut/*real-metadata-cache* (atom {})} ;; [@ (atom {})]
            #(#'sut/ns-state (find-ns 'cider.nrepl.middleware.track-state-test)))}))

(def ^:private fn-test-var nil)
(def ^:private fn-test-def-fn (fn []))
(defn- fn-test-defn-fn [])
(defmulti fn-test-multi (fn [x]))

(defn- test-fn "docstring"
  ([a b] nil)
  ([a] nil)
  ([]))

(defmacro test-macro [a & body])

(deftest ns-state-clj-test
  (is+ '{:aliases {sut cider.nrepl.middleware.track-state
                   matchers matcher-combinators.matchers}
         :interns {test-fn {:fn "true"}
                   testing {:macro "true"}
                   macro-without-style-indent-1 {:macro "true", :style/indent "1"}
                   is {:macro "true"}
                   is+ {:macro "true"}
                   fn-test-multi {:fn "true"}
                   deftest {:macro "true"}
                   fn-test-def-fn {:fn "true"}
                   fn-test-var {}
                   test-macro {:macro "true", :style/indent "1"}}}
       (sut/ns-state (find-ns 'cider.nrepl.middleware.track-state-test))))

(deftest ns-state-cljs-test
  (let [cljs-ns {:use-macros {'test-fn 'cider.nrepl.middleware.track-state-test

                              'test-macro 'cider.nrepl.middleware.track-state-test}
                 :uses {'sym-1 'some-other-cljs-ns}
                 :defs {'a-fn {:fn-var true}
                        'b-fn {:tag 'function}
                        'c-fn {:tag 'cljs.core/MultiFn}
                        'd-fn {}
                        'a-var {:tag 'something}}
                 :macros '{from-macros-with-style-indent {:arglists ([& args]),
                                                          :style/indent :defn,
                                                          :line 387,
                                                          :column 1,
                                                          :file "helix/dom.cljc",
                                                          :name helix.dom/form,
                                                          :ns helix.dom,
                                                          :macro true},
                           from-macros-without-style-indent {:arglists ([& args]),
                                                             :line 246,
                                                             :column 1,
                                                             :file "helix/dom.cljc",
                                                             :name helix.dom/audio,
                                                             :ns helix.dom,
                                                             :macro true}}
                 :require-macros {'sym-2 'some-namespace}
                 :requires {'sym-3 'some-namespace}}
        other-namespaces [{:name 'some-other-cljs-ns
                           :defs {'sym-1 {:meta {:arglists '([] [a] [a b])}}}}]
        {:keys [aliases interns]} (sut/ns-state cljs-ns)]
    (binding [sut/*all-cljs-namespaces* other-namespaces]
      (let [{:keys [aliases interns]} (sut/ns-state cljs-ns)]
        (is (any? (sut/ns-state (dissoc cljs-ns :macros :require-macros :use-macros)))
            "Doesn't throw exceptions in the absence of optional keys")
        (is (= '{sym-2 some-namespace sym-3 some-namespace} aliases))
        (is (= '{a-fn {:fn "true"},
                 b-fn {:fn "true"},
                 c-fn {:fn "true"},
                 d-fn {:fn "true"},
                 a-var {},
                 ;; fetched by traversing `other-namespaces`:
                 sym-1 {:arglists "([] [a] [a b])"},
                 ;; fetched by inspecting the JVM clojure environment:
                 test-fn {:arglists "([a b] [a] [])", :doc "\"docstring\""}
                 ;; adds :style/indent despite it not being originally present:
                 test-macro {:macro "true", :arglists "([a & body])", :style/indent "1"}
                 ;; :style/indent is preserved:
                 from-macros-with-style-indent {:macro "true", :arglists "([& args])", :style/indent ":defn"},
                 ;; :style/indent is inferred:
                 from-macros-without-style-indent {:macro "true", :arglists "([& args])", :style/indent "0"}}
               interns))))))

(deftest calculate-used-aliases-test
  (is (contains? (#'sut/merge-used-aliases @some-ns-map)
                 'cider.nrepl.middleware.track-state)))

(deftest ensure-clojure-core-present
  (testing "if clojurescript doesn't add clojure"
    (binding [sut/*cljs* true]
      (is (not (contains? (#'sut/add-core-namespace-vars {}) 'clojure.core)))))
  (testing "if core missing and not cljs, it adds it"
    (is (contains? (#'sut/add-core-namespace-vars {}) 'clojure.core))))

(defmacro macro-without-style-indent-1 [opts & body])
(defmacro macro-without-style-indent-2 [opts body])
(defmacro macro-without-style-indent-3 [opts baddy])
(defmacro macro-with-explicitly-nil-style-indent {:style/indent nil} [opts & body])

(deftest indentation-inference-test
  (testing "Adds `:style/indent` metadata when it's suitable to do so"
    (is+ {"interns" {"macro-without-style-indent-1" {"style/indent" "1"}
                     "macro-without-style-indent-2" {"style/indent" "1"}
                     "macro-without-style-indent-3" {"style/indent" matchers/absent}
                     "macro-with-explicitly-nil-style-indent" {"style/indent" matchers/absent}}}
         (-> (sut/calculate-changed-project-state-response {:session (atom {})})
             (get-in [:changed-namespaces "cider.nrepl.middleware.track-state-test"])))))

(deftest inferrable-indent?-test
  (testing "clojure.* macros are not inferrable"
    (is (#'sut/inferrable-indent? (meta #'macro-without-style-indent-1)))
    (is (not (#'sut/inferrable-indent? (meta #'defn))))
    (is (not (#'sut/inferrable-indent? (meta #'deftest))))))

(defn- message-and-state [msg]
  (last ((#'nrepl/delimited-transport-seq
          session/*session* #{"state" :state} {:id (nrepl.misc/uuid)}) msg)))

(deftest integration-test
  (session/session-fixture
   (fn []
     (is+ {:changed-namespaces {:cider.nrepl.middleware.track-state-test
                                {:interns #(> (count %) 10)}}}
          (message-and-state {:op   "eval"
                              :code "(+ 1 2)"}))

     (testing "subsequent evaluation reports empty changed state"
       (is+ {:changed-namespaces empty?}
            (message-and-state {:op   "eval"
                                :code "(+ 1 2)"})))

     (testing "modifying metadata reports just updates to that var"
       (is+ {:changed-namespaces
             (matchers/all-of #(= (count %) 1)
                              {:cider.nrepl.middleware.track-state-test
                               {:interns {:fn-test-defn-fn {:fn "true" :deprecated "true"}}}})}
            (message-and-state {:op   "eval"
                                :code "(alter-meta! #'cider.nrepl.middleware.track-state-test/fn-test-defn-fn assoc :deprecated true)"})))
     ;; Restore
     (alter-meta! #'cider.nrepl.middleware.track-state-test/fn-test-defn-fn dissoc :deprecated))))
