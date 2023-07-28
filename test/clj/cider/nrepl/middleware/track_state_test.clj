(ns cider.nrepl.middleware.track-state-test
  (:require
   [cider.nrepl.middleware.track-state :as sut]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.meta :as um]
   [clojure.test :refer :all])
  (:import
   (nrepl.transport Transport)))

(def some-ns-map {'cider.nrepl.middleware.track-state-test
                  (sut/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test)
                                 (all-ns))})

;;; This is to prevent the agent from flooding test reports with
;;; irrelevant exceptions.
(set-error-handler! sut/ns-cache (constantly nil))
(set-error-mode! sut/ns-cache :continue)

(def msg {:session :dummy})

(deftest make-transport-test
  (is (instance? Transport (sut/make-transport msg)))
  (is (try (send (sut/make-transport msg) 10)
           nil
           (catch Exception e true))))

(defn update-and-send-cache-tester
  "Use the other arity of sut/update-and-send-cache to evaluate
  strictly in test mode."
  [old-data msg sent-value]
  (sut/update-and-send-cache old-data msg
                             #{}
                             (fn [t m] (reset! sent-value m))))

(deftest update-and-send-cache-test
  (let [sent-value (atom nil)]
    (let [new-data (update-and-send-cache-tester nil msg sent-value)]
      (is (map? new-data))
      (is (< 80 (count new-data))
          "There are plenty of results returned"))
    (let [{:keys [repl-type changed-namespaces]} @sent-value]
      (is (= :clj repl-type))
      (is (map? changed-namespaces))
      (is (< 80 (count changed-namespaces))
          "There are plenty of results returned"))
    (let [full-cache (update-and-send-cache-tester nil msg sent-value)
          get-sent-value (fn [old] (update-and-send-cache-tester old msg sent-value)
                           @sent-value)]
      ;; Return value depends only on the current state.
      (is (= (update-and-send-cache-tester nil msg sent-value)
             (update-and-send-cache-tester (into {} (take 5 full-cache)) msg sent-value)
             (update-and-send-cache-tester full-cache msg sent-value)))
      ;; Sent message depends on the first arg.
      (is (= (get-sent-value full-cache)
             (get-sent-value full-cache)))
      (is (= (get-sent-value (into {} (drop 3 full-cache)))
             (get-sent-value (into {} (drop 3 full-cache))))))
    ;; In particular, the sent message only contains the diff.

    (let [changed-again (:changed-namespaces @sent-value)]
      (is (map? changed-again))
      (is (= 3 (count changed-again))))
    ;; Check repl-type :cljs

    (with-redefs [cljs/grab-cljs-env (constantly true)]
      (update-and-send-cache-tester nil msg sent-value)
      (let [{:keys [repl-type changed-namespaces]} @sent-value]
        (is (= :cljs repl-type))
        (is (map? changed-namespaces))))))

(def ^:private fn-test-var nil)
(def ^:private fn-test-def-fn (fn []))
(defn- fn-test-defn-fn [])
(defmulti fn-test-multi (fn [x]))

(deftest filter-core-and-get-meta-test
  (is (= (sut/filter-core-and-get-meta {'and #'and, 'b #'map, 'c #'deftest})
         '{c {:macro "true"
              :arglists "([name & body])"
              :fn "true"
              :doc "\"Defines a test function with no arguments.  Test functions may call\\n  other tests, so tests may be composed.  If you compose tests, you\\n  should also define a function named test-ns-hook; run-tests will\\n  call test-ns-hook instead of testing all vars.\\n\\n  Note: Actually, the test body goes in the :test metadata on the var,\\n  and the real function (the value of the var) calls test-var on\\n  itself.\\n\\n  When *load-tests* is false, deftest is ignored.\""}}))
  (is (= [nil "true" "true" "true"]
         (map (comp :fn
                    (sut/filter-core-and-get-meta
                     {'fn-test-var #'fn-test-var
                      'fn-test-def-fn #'fn-test-def-fn
                      'fn-test-defn-fn #'fn-test-defn-fn
                      'fn-test-multi #'fn-test-multi}))
              '[fn-test-var fn-test-def-fn fn-test-defn-fn fn-test-multi])))
  (is (-> (find-ns 'clojure.core)
          ns-map sut/filter-core-and-get-meta
          seq not)))

(defn- test-fn "docstring"
  ([a b] nil)
  ([a] nil)
  ([]))

(defmacro test-macro [a & body])

(deftest ns-as-map-test
  (is (empty? (sut/ns-as-map nil (all-ns))))
  (let [m (meta #'make-transport-test)]
    ;; #'make-transport refers to the deftest, and not the defn
    (->> (interleave um/relevant-meta-keys (range))
         (apply hash-map)
         (alter-meta! #'make-transport-test merge))
    ;; note: this test inspects the current namespace, so the
    ;; test conditions below may change as the namespace declaration
    ;; evolves.
    (let [{:keys [interns aliases] :as ns}
          (sut/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test)
                         (all-ns))]
      (is (< 5 (count interns)))
      (is (map? interns))
      (is (get interns 'ns-as-map-test))
      (is (:test (get interns 'ns-as-map-test))
          (pr-str (get interns 'ns-as-map-test)))
      (is (= (into #{} (keys (get interns 'make-transport-test)))
             (into #{} um/relevant-meta-keys)))
      (is (= 3 (count aliases)))
      (is (= 'cider.nrepl.middleware.track-state (aliases 'sut))))
    (alter-meta! #'make-transport-test (fn [x y] y) m))
  (let [{:keys [interns aliases] :as ns}
        (sut/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test)
                       (all-ns))]
    (is interns)))

(deftest ns-as-map-cljs-test
  (let [cljs-ns {:use-macros {'test-fn 'cider.nrepl.middleware.track-state-test

                              'test-macro 'cider.nrepl.middleware.track-state-test}
                 :uses {'sym-1 'some-other-cljs-ns}
                 :defs {'a-fn {:fn-var true}
                        'b-fn {:tag 'function}
                        'c-fn {:tag 'cljs.core/MultiFn}
                        'd-fn {}
                        'a-var {:tag 'something}}
                 :require-macros {'sym-2 'some-namespace}
                 :requires {'sym-3 'some-namespace}}
        other-namespaces [{:name 'some-other-cljs-ns
                           :defs {'sym-1 {:meta {:arglists '([] [a] [a b])}}}}]
        {:keys [aliases interns]} (sut/ns-as-map cljs-ns other-namespaces)]
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
             test-macro {:macro "true", :arglists "([a & body])", :style/indent "1"}}
           interns))))

(deftest calculate-used-aliases-test
  (is (contains? (sut/merge-used-aliases some-ns-map nil ns-name (all-ns) sut/ns-as-map)
                 'cider.nrepl.middleware.track-state))
  (is (contains? (sut/merge-used-aliases some-ns-map {'cider.nrepl.middleware.track-state nil} ns-name (all-ns) sut/ns-as-map)
                 'cider.nrepl.middleware.track-state))
  (is (contains? (sut/merge-used-aliases (assoc some-ns-map 'cider.nrepl.middleware.track-state nil) nil ns-name (all-ns) sut/ns-as-map)
                 'cider.nrepl.middleware.track-state)))

(deftest ensure-clojure-core-present
  (testing "if clojurescript doesn't add clojure"
    ;; note that the {:msg :stuff} object is much more complex in
    ;; actual use and in fact the msg is much more complicated
    (is (-> (sut/ensure-clojure-core-present {}
                                             {'cljs.core :present}
                                             {:msg :stuff}
                                             (all-ns)
                                             sut/ns-as-map)
            keys
            #{sut/clojure-core}
            not)))
  (testing "if core already present doesn't overwrite or add"
    (is (= :present
           (-> (sut/ensure-clojure-core-present {}
                                                {sut/clojure-core :present}
                                                nil
                                                (all-ns)
                                                sut/ns-as-map)
               (get sut/clojure-core)))))
  (testing "if core missing and not cljs, it adds it"
    (is (= sut/clojure-core-map
           (-> (sut/ensure-clojure-core-present {} {} nil (all-ns) sut/ns-as-map)
               (get sut/clojure-core))))))

(defmacro macro-without-style-indent-1 [opts & body])
(defmacro macro-without-style-indent-2 [opts body])
(defmacro macro-without-style-indent-3 [opts baddy])

(def mock-msg (reify nrepl.transport/Transport
                (recv [this])
                (recv [this timeout])
                (send [this msg])))

(deftest indentation-inference-test
  (testing "Adds `:style/indent` metadata when it's suitable to do so"
    (let [cache (sut/update-and-send-cache nil
                                           {:transport mock-msg})
          interns (-> cache
                      (get 'cider.nrepl.middleware.track-state-test)
                      :interns)]
      (is (= "1"
             (-> interns (get 'macro-without-style-indent-1) :style/indent))
          (pr-str (-> interns (get 'macro-without-style-indent-1))))
      (is (= "1"
             (-> interns (get 'macro-without-style-indent-2) :style/indent)))
      (is (= nil
             (-> interns (get 'macro-without-style-indent-3) :style/indent))))))

(comment
  (let [cache (sut/update-and-send-cache nil
                                         {:transport mock-msg})
        interns (-> cache
                    (get 'cider.nrepl.middleware.track-state-test)
                    :interns)]
    (keys cache)))

(deftest inferrable-indent?-test
  (testing "clojure.* macros are not inferrable"
    (is (#'sut/inferrable-indent? (meta #'macro-without-style-indent-1)))
    (is (not (#'sut/inferrable-indent? (meta #'defn))))
    (is (not (#'sut/inferrable-indent? (meta #'deftest))))))

(defn proxied
  "Docstring"
  {:style/indent 1}
  ([])
  ([a b c]))

(def proxy-by-var-quote #'proxied)

(def proxy-by-var-symbol proxied)

(deftest merge-meta-from-proxied-var-test
  (testing "Copies `:doc`, `:style/indent` and `:arglist` metadata from the proxied var to the proxy var"
    (testing "For a var which value is var"
      (is (= {:doc "Docstring"
              :arglists '([] [a b c])
              :style/indent 1}
             (select-keys (#'sut/merge-meta-from-proxied-var (meta #'proxy-by-var-quote) #'proxy-by-var-quote)
                          [:doc :arglists :style/indent]))))

    (testing "For a var which value is an object, which at read-time is expressed as a single symbol"
      (is (= {:doc "Docstring"
              :style/indent 1
              :arglists '([] [a b c])}
             (select-keys (#'sut/merge-meta-from-proxied-var (meta #'proxy-by-var-symbol) #'proxy-by-var-symbol)
                          [:doc :arglists :style/indent]))))))
