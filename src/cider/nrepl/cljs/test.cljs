(ns cider.nrepl.cljs.test
  "ClojureScript-runtime helper for the test middleware.

  This namespace is compiled into the user's ClojureScript runtime on demand
  (via `require`) by `cider.nrepl.middleware.test`. It installs a custom
  `cljs.test/report` reporter that captures each assertion as plain, EDN-safe
  data, so the JVM side can read the results back and reshape them into the same
  report the Clojure test middleware produces. See clojure-emacs/cider#555."
  (:require
   [cljs.test :as t]))

(defonce ^:private state (atom nil))

(defn clear!
  "Reset the captured test state. Call before a run."
  []
  (reset! state {:done? false
                 :results []
                 :summary {:ns 0 :var 0 :test 0 :pass 0 :fail 0 :error 0}}))

(defn collect
  "Return the captured test state as EDN-safe data."
  []
  @state)

(defn poll
  "Return the full captured state once the run has completed (`:end-run-tests`
  fired), or a lightweight `{:done? false}` while it's still running. Used by the
  JVM side to await asynchronous (`cljs.test/async`) tests without blocking the
  runtime's event loop."
  []
  (if (:done? @state)
    @state
    {:done? false}))

(defn- current-var []
  (first (:testing-vars (t/get-current-env))))

(defn- error-data [e]
  (if (instance? js/Error e)
    {:message (.-message e) :stack (.-stack e)}
    {:message (pr-str e)}))

(defn- record! [result-type m]
  (let [vm (meta (current-var))
        env (t/get-current-env)]
    (swap! state
           (fn [s]
             (-> s
                 (update-in [:summary :test] inc)
                 (update-in [:summary result-type] inc)
                 (update :results conj
                         (cond-> {:type result-type
                                  :ns (str (:ns vm))
                                  :var (str (:name vm))}
                           (:message m)
                           (assoc :message (:message m))

                           (seq (:testing-contexts env))
                           (assoc :context (t/testing-contexts-str))

                           (contains? m :expected)
                           (assoc :expected (pr-str (:expected m)))

                           (#{:fail :error} result-type)
                           (assoc :actual (pr-str (:actual m)))

                           (:line m)
                           (assoc :line (:line m))

                           (= :error result-type)
                           (assoc :error (error-data (:actual m))))))))))

(defmethod t/report [::reporter :pass] [m] (record! :pass m))
(defmethod t/report [::reporter :fail] [m] (record! :fail m))
(defmethod t/report [::reporter :error] [m] (record! :error m))

(defmethod t/report [::reporter :begin-test-ns] [_m]
  (swap! state update-in [:summary :ns] inc))

(defmethod t/report [::reporter :begin-test-var] [_m]
  (swap! state update-in [:summary :var] inc))

(defmethod t/report [::reporter :end-test-var] [_m])

(defmethod t/report [::reporter :end-run-tests] [_m]
  ;; Fires once the whole run (including async tests) has finished.
  (swap! state assoc :done? true))

(defmethod t/report [::reporter :default] [_m])
