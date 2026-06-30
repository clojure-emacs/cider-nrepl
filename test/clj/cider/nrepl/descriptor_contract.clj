(ns cider.nrepl.descriptor-contract
  "Maintenance check verifying that the op descriptors are an honest contract:
  every response key an op actually emits should be documented in that op's
  `:returns` (or be a standard nREPL / error-handling key).

  Unlike the static checks in `cider.nrepl.descriptor-test`, this one has to
  observe the ops *running*, so it drives the op test suite with the test
  session's `message` fn instrumented to record response keys. Run it with:

    make descriptor-contract

  It complements, rather than duplicates, the unit tests: those run on every
  change; this is a slower, suite-wide audit."
  (:require
   [cider.nrepl]
   [cider.nrepl.middleware :as mw]
   [cider.nrepl.test-session :as ts]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [clojure.tools.namespace.find :as find]))

;; Standard response keys that aren't op-specific :returns.
(def standard-keys
  #{:id :session :status :new-session :op :nrepl.middleware.print/keys
    ;; error-path keys added by the error-handling middleware on any failure:
    :err :ex :root-ex :pp-stacktrace :nrepl.middleware.print/error})

;; nREPL-standard ops whose returns cider doesn't own.
(def skip-ops #{"eval" "describe" "clone" "close" "ls-sessions" "stdin" "interrupt"})

(defn- documented-returns
  "op-name -> set of documented :returns keys (as keywords)."
  []
  (into {}
        (for [w mw/cider-middleware
              :let [d (:nrepl.middleware/descriptor (meta (resolve w)))]
              [op od] (:handles d)]
          [op (set (map keyword (keys (:returns od))))])))

(defn- response-keys [resp]
  (cond (map? resp) (keys resp)
        (sequential? resp) (mapcat keys resp)
        :else nil))

(defn- run-suite-recording-returns
  "Run every op test namespace with `message` instrumented; return op -> set of
  observed response keys."
  []
  (let [seen (atom {})
        orig ts/message]
    (alter-var-root #'ts/message
                    (constantly
                     (fn [& args]
                       (let [resp (apply orig args)]
                         (when-let [op (:op (first args))]
                           (swap! seen update op (fnil into #{}) (response-keys resp)))
                         resp))))
    (try
      (let [nses (mapcat #(find/find-namespaces-in-dir (io/file %))
                         ["test/clj" "test/common"])]
        (run! require nses)
        (binding [t/*test-out* (java.io.StringWriter.)] ;; silence the test report
          (apply t/run-tests nses)))
      (finally
        (alter-var-root #'ts/message (constantly orig))))
    @seen))

(defn violations
  "Seq of [op undocumented-keys] for ops emitting keys not in their :returns."
  []
  (let [documented (documented-returns)]
    (for [[op ks] (sort (run-suite-recording-returns))
          :when (not (skip-ops op))
          :let [doc (get documented op #{})
                undoc (vec (sort (remove #(or (standard-keys %) (doc %)) ks)))]
          :when (seq undoc)]
      [op undoc])))

(defn -main [& _]
  (let [vs (violations)]
    (if (seq vs)
      (do
        (println "Descriptor return contract FAILED. These ops emit response keys"
                 "not documented in their :returns (add them, or add to skip-ops):")
        (doseq [[op ks] vs] (println " " op (pr-str ks)))
        (shutdown-agents)
        (System/exit 1))
      (do
        (println "Descriptor return contract OK.")
        (shutdown-agents)))))
