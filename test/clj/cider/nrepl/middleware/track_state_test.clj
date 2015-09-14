(ns cider.nrepl.middleware.track-state-test
  (:require [cider.nrepl.middleware.track-state :as s]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [clojure.test :refer :all]
            [clojure.tools.nrepl.transport :as t])
  (:import clojure.tools.nrepl.transport.Transport))

(def ^:const msg {:session :dummy})

(deftest make-transport
  (is (instance? Transport (s/make-transport msg)))
  (is (try (send (s/make-transport msg) 10)
           nil
           (catch Exception e true))))

(deftest track-ns?
  (is (s/track-ns? 'me.ok))
  (is (s/track-ns? 'batman.not-bruce))
  (is (not (s/track-ns? 'mranderson045.a)))
  (is (not (s/track-ns? 'cider.nrepl.middleware.track-state)))
  (is (not (s/track-ns? 'deps.pl)))
  (is (not (s/track-ns? 'eastwood.copieddeps))))

(deftest assoc-state
  (with-redefs [s/ns-cache (atom {})
                s/track-ns? (constantly true)]
    (let [{:keys [repl-type changed-namespaces]} (:state (s/assoc-state {} msg))]
      (is (= repl-type :clj))
      (is (map? changed-namespaces))
      (is (> (count changed-namespaces) 100)))
    ;; Check the caching
    (let [changed-again (get-in (s/assoc-state {} msg) [:state :changed-namespaces])]
      (is (map? changed-again))
      (is (empty? changed-again)))
    ;; Remove a value
    (swap! s/ns-cache update-in [:dummy]
           #(dissoc % (ffirst %)))
    ;; Check again
    (let [changed-again (get-in (s/assoc-state {} msg) [:state :changed-namespaces])]
      (is (= (count changed-again) 1))))
  ;; Check repl-type :cljs
  (with-redefs [cljs/grab-cljs-env (constantly true)
                s/ns-cache (atom {})]
    (let [{:keys [repl-type changed-namespaces]} (:state (s/assoc-state {} msg))]
      (is (= repl-type :cljs))
      (is (map? changed-namespaces)))))

(deftest update-vals
  (is (= (s/update-vals inc {1 2 3 4 5 6})
         {1 3 3 5 5 7}))
  (is (= (s/update-vals range {1 2 3 4 5 6})
         '{5 (0 1 2 3 4 5), 3 (0 1 2 3), 1 (0 1)}))
  (is (= (s/update-vals str {:a :b :c :d :e :f})
         {:e ":f", :c ":d", :a ":b"}))
  (is (= (s/update-vals odd? {1 2 3 4 5 6})
         {1 false 3 false 5 false})))

(deftest filter-core
  (is (= (s/filter-core {'and #'and, 'b #'map, 'c #'deftest})
         {'c #'clojure.test/deftest}))
  (is (-> (find-ns 'clojure.core)
          ns-interns s/filter-core
          seq not)))

(deftest relevant-meta
  (is (= (:macro (s/relevant-meta #'deftest))
         "true"))
  (alter-meta! #'update-vals merge {:indent 1 :cider-instrumented 2 :something-else 3})
  (is (= (s/relevant-meta #'update-vals)
         {:cider-instrumented "2", :indent "1",
          :test (pr-str (:test (meta #'update-vals)))})))

(deftest ns-as-map
  (alter-meta! #'update-vals
               merge {:indent 1 :cider-instrumented 2 :something-else 3})
  (with-redefs [s/track-ns? (constantly true)]
    (let [{:keys [interns aliases] :as ns} (s/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test))]
      (is (> (count ns) 3))
      (is (> (count interns) 4))
      (is (= (into #{} (keys (interns 'update-vals)))
             #{:cider-instrumented :indent :test}))
      (is (> (count aliases) 2))
      (is (= (aliases 's)
             'cider.nrepl.middleware.track-state))))
  (with-redefs [s/track-ns? (constantly nil)]
    (let [{:keys [interns aliases] :as ns}
          (s/ns-as-map (find-ns 'cider.nrepl.middleware.track-state-test))]
      (is (not ns)))))
