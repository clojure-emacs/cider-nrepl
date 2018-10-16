(ns smoketest.core
  (:require
   [nrepl.core :as nrepl]
   [nrepl.server :refer [start-server]])
  (:gen-class))

;; The cider-nrepl "smoke test" replicates a small sampling of the
;; library's unit test coverage executing in an uberjar. The point of
;; this test is to confirm the cider-nrepl artifact compiles,
;; installs, and can be used in a standalone jar. Comprehensive test
;; coverage is not a goal of this smoke test.


;; see cider-nrepl issue #447
(defn nrepl-handler []
  (require 'cider.nrepl)
  (ns-resolve 'cider.nrepl 'cider-nrepl-handler))

(defn nrepl-server-fixture
  "Derived from the cider-nrepl test fixture.  Launch the nrepl server,
  establish a client session, and call the function f with the client
  session as its sole argument."
  [f]
  (with-open [server (start-server :bind "localhost" :handler (nrepl-handler))
              ;; for now binding "localhost" circumvents the bug
              ;; https://dev.clojure.org/jira/browse/NREPL-87
              transport (nrepl/connect :port (:port server))]
    (let [client (nrepl/client transport Long/MAX_VALUE)
          session (nrepl/client-session client)]
      (f session))))

(defn message
  "Send message to session and return the combined response."
  [session msg]
  (let [responses (nrepl/message session msg)]
    (nrepl/combine-responses responses)))

;; Tests are lifted from the unit test coverage and rewritten to
;; execute without the clojure.test framework. This results in some
;; repetition but keeps this smoke test simple and lightweight.

(defn check-version
  "Call version middleware and check response."
  [session]
  ;; This test generates reflection warnings in java9, but passes.
  (let [response (message session {:op :cider-version})
        version-map (:cider-version response)]
    (and (= #{"done"} (:status response))
         (contains? version-map :major)
         (contains? version-map :minor)
         (contains? version-map :incremental)
         (contains? version-map :version-string))))

(defn check-classpath
  "Call classpath middleware and check response."
  [session]
  (let [response (message session {:op :classpath})
        classpaths (:classpath response)]
    (and (= (:status response) #{"done"})
         (> (count classpaths) 0)
         (every? string? classpaths)
         (some? (some #(re-find #".*smoketest-.*standalone\.jar" %) classpaths)))))

(defn check-ns-path
  "Call ns middleware and check response."
  [session]
  (let [response (message session {:op :ns-path :ns "cider.nrepl"})
        ns-path (:path response)]
    (.endsWith ns-path "cider/nrepl.clj")))


;; For simplistic reporting: {"test1" true, "test2" false, ... }

(def testnames ["check-version"
                "check-classpath"
                "check-ns-path"])

(def tests (apply juxt (map (comp resolve symbol) testnames)))

(defn -main
  "Execute all smoke tests and exit 0 (or 1) to signal
  success (or failure) to CI."
  []
  (let [results (nrepl-server-fixture tests)]
    (clojure.pprint/pprint (zipmap testnames results))
    (shutdown-agents)
    (when-not (every? identity results)
      (println "smoketest: FAIL")
      (System/exit 1)))
  (println "smoketest: OK"))
