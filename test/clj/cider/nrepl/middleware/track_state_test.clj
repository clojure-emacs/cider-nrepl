(ns cider.nrepl.middleware.track-state-test
  (:require [cider.nrepl.middleware.track-state :as st]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [orchard.meta :as m]
            [orchard.namespace :as namespace]
            [clojure.test :refer :all]
            [clojure.tools.nrepl.transport :as t])
  (:import clojure.tools.nrepl.transport.Transport))

(def some-ns-map {'cider.nrepl.middleware.track-state-test
                  (st/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test))})

;;; This is to prevent the agent from flooding test reports with
;;; irrelevant exceptions.
(set-error-handler! st/ns-cache (constantly nil))
(set-error-mode! st/ns-cache :continue)

(def ^:const msg {:session :dummy})

(deftest make-transport-test
  (is (instance? Transport (st/make-transport msg)))
  (is (try (send (st/make-transport msg) 10)
           nil
           (catch Exception e true))))

(deftest update-and-send-cache-test
  (let [sent-value (atom nil)]
    (with-redefs [namespace/jar-namespaces #{}
                  t/send (fn [t m] (reset! sent-value m))]
      (let [new-data (st/update-and-send-cache nil msg)]
        (is (map? new-data))
        (is (> (count new-data) 100)))
      (let [{:keys [repl-type changed-namespaces]} @sent-value]
        (is (= repl-type :clj))
        (is (map? changed-namespaces))
        (is (> (count changed-namespaces) 100)))
      (let [full-cache (st/update-and-send-cache nil msg)
            get-sent-value (fn [old] (st/update-and-send-cache old msg)
                             @sent-value)]
        ;; Return value depends only on the current state.
        (is (= (st/update-and-send-cache nil msg)
               (st/update-and-send-cache (into {} (take 5 full-cache)) msg)
               (st/update-and-send-cache full-cache msg)))
        ;; Sent message depends on the first arg.
        (is (= (get-sent-value full-cache)
               (get-sent-value full-cache)))
        (is (= (get-sent-value (into {} (drop 3 full-cache)))
               (get-sent-value (into {} (drop 3 full-cache))))))
      ;; In particular, the sent message only contains the diff.
      (let [changed-again (:changed-namespaces @sent-value)]
        (is (map? changed-again))
        (is (= (count changed-again) 3)))
      ;; Check repl-type :cljs
      (with-redefs [cljs/grab-cljs-env (constantly true)]
        (st/update-and-send-cache nil msg)
        (let [{:keys [repl-type changed-namespaces]} @sent-value]
          (is (= repl-type :cljs))
          (is (map? changed-namespaces)))))))

(def ^:private fn-test-var nil)
(def ^:private fn-test-def-fn (fn []))
(defn- fn-test-defn-fn [])
(defmulti fn-test-multi (fn [x]))

(deftest filter-core-and-get-meta-test
  (is (= (st/filter-core-and-get-meta {'and #'and, 'b #'map, 'c #'deftest})
         '{c {:macro "true"
              :arglists "([name & body])"
              :fn "true"
              :doc "\"Defines a test function with no arguments.  Test functions may call\\n  other tests, so tests may be composed.  If you compose tests, you\\n  should also define a function named test-ns-hook; run-tests will\\n  call test-ns-hook instead of testing all vars.\\n\\n  Note: Actually, the test body goes in the :test metadata on the var,\\n  and the real function (the value of the var) calls test-var on\\n  itself.\\n\\n  When *load-tests* is false, deftest is ignored.\""}}))
  (is (= (map (comp :fn
                    (st/filter-core-and-get-meta
                     {'fn-test-var #'fn-test-var
                      'fn-test-def-fn #'fn-test-def-fn
                      'fn-test-defn-fn #'fn-test-defn-fn
                      'fn-test-multi #'fn-test-multi}))
              '[fn-test-var fn-test-def-fn fn-test-defn-fn fn-test-multi])
         [nil "true" "true" "true"]))
  (is (-> (find-ns 'clojure.core)
          ns-map st/filter-core-and-get-meta
          seq not)))

(defn- test-fn "docstring"
  ([a b] nil)
  ([a] nil)
  ([]))

(deftest ns-as-map-test
  (is (empty? (st/ns-as-map nil)))
  (let [m (meta #'make-transport-test)]
    ;; #'make-transport refers to the deftest, and not the defn
    (->> (interleave m/relevant-meta-keys (range))
         (apply hash-map)
         (alter-meta! #'make-transport-test merge))
    (let [{:keys [interns aliases] :as ns}
          (st/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test))]
      (is (> (count interns) 5))
      (is (map? interns))
      (is (interns 'ns-as-map-test))
      (is (:test (interns 'ns-as-map-test)))
      (is (= (into #{} (keys (interns 'make-transport-test)))
             (into #{} m/relevant-meta-keys)))
      (is (> (count aliases) 2))
      (is (= (aliases 'st)
             'cider.nrepl.middleware.track-state)))
    (alter-meta! #'make-transport-test (fn [x y] y) m))
  (let [{:keys [interns aliases] :as ns}
        (st/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test))]
    (is interns)))

(deftest ns-as-map-cljs-test
  (let [cljs-ns {:use-macros {'sym-0 #'test-fn}
                 :uses {'sym-1 #'ns-as-map-cljs-test}
                 :defs {'sym-2 #'ns-as-map-cljs-test
                        'a-fn {:fn-var true}
                        'a-var {}}
                 :require-macros {'sym-3 'some-namespace}
                 :requires {'sym-4 'some-namespace}}
        {:keys [aliases interns]} (st/ns-as-map cljs-ns)]
    (is (= aliases '{sym-3 some-namespace sym-4 some-namespace}))
    (is (= interns '{sym-0 {:arglists ([]) :macro true}
                     sym-1 {:arglists ([])}
                     sym-2 {}
                     a-var {}
                     a-fn {:fn "true"}}))))

(deftest calculate-used-aliases-test
  (is (contains? (st/merge-used-aliases some-ns-map nil ns-name)
                 'cider.nrepl.middleware.track-state))
  (is (contains? (st/merge-used-aliases some-ns-map {'cider.nrepl.middleware.track-state nil} ns-name)
                 'cider.nrepl.middleware.track-state))
  (is (contains? (st/merge-used-aliases (assoc some-ns-map 'cider.nrepl.middleware.track-state nil) nil ns-name)
                 'cider.nrepl.middleware.track-state)))

(deftest ensure-clojure-core-present
  (testing "if clojurescript doesn't add clojure"
    ;; note that the {:msg :stuff} object is much more complex in
    ;; actual use and in fact the msg is much more complicated
    (is (-> (st/ensure-clojure-core-present {}
                                            {'cljs.core :present}
                                            {:msg :stuff})
            keys
            #{st/clojure-core}
            not)))
  (testing "if core already present doesn't overwrite or add"
    (is (= :present
           (-> (st/ensure-clojure-core-present {}
                                               {st/clojure-core :present}
                                               nil)
               (get st/clojure-core)))))
  (testing "if core missing and not cljs, it adds it"
    (is (= st/clojure-core-map
           (-> (st/ensure-clojure-core-present {} {} nil)
               (get st/clojure-core))))))
