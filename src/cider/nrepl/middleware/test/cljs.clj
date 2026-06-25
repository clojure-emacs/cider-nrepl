(ns cider.nrepl.middleware.test.cljs
  "ClojureScript support for the test middleware.

  ClojureScript tests can only run in the JS runtime, and `eval` is the only way
  to reach it. So, much like the Clojure path drives execution through `eval` for
  interruptibility, here we rewrite the test op into an `eval` op that runs the
  tests via `cljs.test` in the runtime, capturing each assertion as EDN-safe data
  (see `cider.nrepl.cljs.test`), then reshape that data into the very same report
  the Clojure path produces. See clojure-emacs/cider#555.

  Current limitations: only synchronous tests are supported (`cljs.test/async`
  tests aren't awaited); `fail-fast` is ignored (`cljs.test` has no equivalent);
  and running specific vars runs (but only reports) their whole namespace, since
  fixtures are namespace-scoped."
  (:require
   [cider.nrepl.middleware.util :as util :refer [respond-to]]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [base-error-response eval-interceptor-transport]]
   [clojure.string :as str]
   [nrepl.middleware.caught :as caught]
   [orchard.misc :as misc])
  (:import
   (nrepl.transport Transport)))

(def ^:private reporter
  "The `cljs.test` reporter keyword installed by `cider.nrepl.cljs.test`."
  :cider.nrepl.cljs.test/reporter)

(def results
  "An atom holding the most recent ClojureScript test results, keyed by namespace
  and var, mirroring the Clojure path's store so `retest` can re-run failures."
  (atom {}))

;;; ## Determining what to run

(defn- targets
  "Derive the run targets from a test message. Returns a map with `:all?` (run
  every loaded test namespace), `:nss` (namespace symbols to run), and `:vars`
  (a set of fully-qualified var symbols to filter the report to, or nil for all)."
  [{:keys [op ns tests var-query]}]
  (case op
    ("cider/test-all" "test-all")
    {:all? true}

    ("cider/test" "test")
    {:nss (when ns [(misc/as-sym ns)])
     :vars (when (seq tests)
             (set (map #(misc/as-sym (str ns "/" %)) tests)))}

    ;; cider/test-var-query
    (let [{:keys [ns-query exactly]} var-query]
      (if (:project? ns-query)
        {:all? true}
        (let [vars (when (seq exactly) (set (map misc/as-sym exactly)))]
          {:nss (or (seq (map misc/as-sym (:exactly ns-query)))
                    ;; Fall back to the namespaces of the requested vars.
                    (distinct (map (comp symbol namespace) vars)))
           :vars vars})))))

;;; ## Running the tests

(def ^:private require-code
  "ClojureScript that loads the runtime helper. It must be evaluated as its own
  message before the run code: cljs can't use a namespace's vars in the same
  compilation unit that requires it."
  "(require 'cljs.test 'cider.nrepl.cljs.test)")

(defn- run-code
  "Build the ClojureScript program (as a string) that runs the requested tests
  with our capturing reporter and returns the collected data."
  [{:keys [all? nss]}]
  (let [run (if all?
              (format "(cljs.test/run-all-tests nil (cljs.test/empty-env %s))" reporter)
              (format "(cljs.test/run-tests (cljs.test/empty-env %s) %s)"
                      reporter
                      (str/join " " (map #(str "'" %) nss))))]
    (format "(do (cider.nrepl.cljs.test/clear!) %s (cider.nrepl.cljs.test/collect))"
            run)))

;;; ## Reshaping the results

(defn- reshape
  "Turn the EDN data captured in the runtime into the report shape the Clojure
  path produces: `{:summary {...} :results {ns {var [assertion-maps]}}}`. When
  `vars` is non-nil, keep only assertions for those vars and recompute the
  summary so it matches what's reported; otherwise use the runtime's own summary,
  which also counts vars/namespaces that produced no assertions."
  [{captured :results, summary :summary} vars]
  (let [keep? (fn [{:keys [ns var]}]
                (or (nil? vars)
                    (contains? vars (symbol (str ns "/" var)))))
        kept (filterv keep? captured)
        nested (reduce (fn [acc {:keys [ns var] :as m}]
                         (let [ns (symbol ns), var (symbol var)
                               idx (count (get-in acc [ns var]))]
                           (update-in acc [ns var] (fnil conj [])
                                      (assoc m :ns ns :var var :index idx))))
                       {}
                       kept)
        by-type (frequencies (map :type kept))]
    {:results nested
     :summary (if vars
                {:ns (count nested)
                 :var (reduce + (map count (vals nested)))
                 :test (count kept)
                 :pass (get by-type :pass 0)
                 :fail (get by-type :fail 0)
                 :error (get by-type :error 0)}
                summary)}))

(defn- reply [msg vars response]
  (let [data (cljs/response-value msg response)
        report (reshape data vars)]
    (reset! results (:results report))
    (respond-to msg (util/transform-value report))))

(defn- eval-cljs
  "Send `code` to be evaluated in the ClojureScript runtime, using `transport` to
  intercept the result."
  [handler msg code transport]
  (handler (assoc msg
                  :op "eval"
                  :code code
                  :transport transport
                  :nrepl.middleware.print/keys [])))

(defn- swallowing-transport
  "A transport that discards the (irrelevant) responses of the helper-loading
  eval and invokes `on-done` once it completes. If the eval itself errored, the
  error is forwarded to the client and `on-done` is not invoked."
  [{:keys [^Transport transport] :as msg} on-done]
  (let [error (atom nil)]
    (reify Transport
      (recv [_this] (.recv transport))
      (recv [_this timeout] (.recv transport timeout))
      (send [this response]
        (cond
          (and (contains? (:status response) :eval-error)
               (contains? response ::caught/throwable))
          (reset! error (::caught/throwable response))

          (contains? (:status response) :done)
          (if-let [e @error]
            (.send transport (base-error-response msg e :done :test-error))
            (on-done)))
        this))))

(defn- missing-namespaces
  "Of `nss`, the ones not present in the running ClojureScript compiler env (so
  they can't be tested). Returns nil when the env can't be inspected."
  [msg nss]
  (when-let [known (some-> (cljs/grab-cljs-env msg)
                           (get :cljs.analyzer/namespaces)
                           keys
                           set)]
    (seq (remove known nss))))

(defn- run-tests [handler msg {:keys [all? nss] :as target}]
  (if (and (not all?) (missing-namespaces msg nss))
    (respond-to msg :status #{:namespace-not-found :done})
    (let [run (fn []
                (eval-cljs handler msg (run-code target)
                           (eval-interceptor-transport
                            msg (fn [m response] (reply m (:vars target) response)) :test-error)))]
      ;; Load the runtime helper first (separate message), then run the tests.
      (eval-cljs handler msg require-code (swallowing-transport msg run)))))

;;; ## Op handlers

(defn- handle-retest [handler msg]
  (let [failed (for [[ns vars] @results
                     [var assertions] vars
                     :when (some (comp #{:fail :error} :type) assertions)]
                 (symbol (str ns "/" var)))]
    (if (seq failed)
      (run-tests handler msg {:nss (distinct (map (comp symbol namespace) failed))
                              :vars (set failed)})
      (do (reset! results {})
          (respond-to msg (util/transform-value {:summary {:ns 0 :var 0 :test 0 :pass 0 :fail 0 :error 0}
                                                 :results {}}))
          (respond-to msg :status :done)))))

(defn- handle-stacktrace [msg]
  (let [[ns var] (map misc/as-sym [(:ns msg) (:var msg)])
        {:keys [message stack]} (get-in @results [ns var (:index msg) :error])]
    (if message
      (respond-to msg
                  :class "js/Error"
                  ;; CIDER renders JVM stack frames; a JS stack is just text, so
                  ;; surface it as part of the message for now.
                  :message (cond-> message stack (str "\n" stack))
                  :status :done)
      (respond-to msg :status #{:no-error :done}))))

(defn handle-test-cljs
  "Handle a test op against an active ClojureScript REPL. Returns true when the
  op was handled here, nil otherwise (so the caller can fall through)."
  [handler {:keys [op] :as msg}]
  (case op
    ("cider/test" "test"
                  "cider/test-all" "test-all"
                  "cider/test-var-query" "test-var-query")
    (do (run-tests handler msg (targets msg)) true)

    ("cider/retest" "retest")
    (do (handle-retest handler msg) true)

    ("cider/test-stacktrace" "test-stacktrace")
    (do (handle-stacktrace msg) true)

    nil))
