(ns ^:debugger cider.nrepl.middleware.enlighten-test
  (:require
   [cider.nrepl.middleware.debug :as d]
   [cider.nrepl.middleware.enlighten :as e]
   [cider.test-helpers :refer [is+]]
   [clojure.test :refer [deftest is]]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.transport :as t]))

(defn- enlighten-run
  "Eval FORM with enlighten, invoke the resulting var with ARGS, and return the
  vector of messages sent over the debug channel."
  [form & args]
  (let [sent (atom [])]
    (with-redefs [d/debugger-message (atom {:transport :fake :id "ID" :session "S"})
                  t/send (fn [_ msg] (swap! sent conj msg))]
      (binding [*msg* {:file "x.clj" :id "ID" :line 1 :column 1}]
        ;; default a line/column, but let the form's own metadata win
        (let [v (e/eval-with-enlighten (vary-meta form #(merge {:line 1 :column 1} %)))]
          (apply v args))))
    @sent))

(defn- enlighten-values
  "The set of reported values (as strings) for FORM run with ARGS."
  [form & args]
  (->> (apply enlighten-run form args) (keep :debug-value) set))

(defn- survives?
  "Whether FORM can be enlightened and run with ARGS without throwing."
  [form & args]
  (try (apply enlighten-run form args) :ok
       (catch Throwable e (str (.getMessage e)))))

(deftest eval-with-enlighten-uses-per-form-line
  ;; Overlays are placed by the form's own line, not the eval's start line, so
  ;; enlighten works when one evaluation spans several top-level forms.
  (let [msg (->> (enlighten-run (with-meta '(defn enl-sample [] (+ 1 2))
                                  {:line 42 :column 5}))
                 (filter :debug-value) first)]
    ;; `:line` is positioned by the form's own line, not the eval's start line.
    (is+ {:status #{:enlighten}
          :line 42}
         msg)))

(deftest reports-every-subexpression-value
  ;; The whole computation lights up, not just the return value and locals.
  (let [values (enlighten-values '(defn f [x] (+ (* x 2) 1)) 3)]
    (is (contains? values "3") "the local x")
    (is (contains? values "6") "(* x 2)")
    (is (contains? values "7") "(+ (* x 2) 1) and the return")))

(deftest reports-conditionals-and-bindings
  (is (contains? (enlighten-values '(defn f [x] (if (pos? x) :pos :neg)) 5) ":pos"))
  (is (contains? (enlighten-values '(defn f [x] (let [y (* x 3)] (inc y))) 2) "7")))

(deftest survives-tricky-forms
  ;; Forms in tail/binding/special positions must not be broken by wrapping.
  (is (= :ok (survives? '(defn f [n] (loop [i 0 acc 0]
                                       (if (< i n) (recur (inc i) (+ acc i)) acc))) 4))
      "loop/recur stays in tail position")
  (is (= :ok (survives? '(defn f [x] (-> x inc (* 2))) 3))
      "threading macro")
  (is (= :ok (survives? '(defn f [] (try (/ 1 0) (catch ArithmeticException _ :caught)))))
      "try/catch")
  (is (= :ok (survives? '(defn f [xs] (map inc xs)) [1 2 3]))
      "lazy seq / higher-order"))

(deftest enlightens-deftest-bodies
  ;; #382: `deftest` stores its body in the var's `:test` metadata (the def value
  ;; just calls `test-var`), so enlightening it has to reach the metadata. The
  ;; body lights up when the test runs. (A minor gap remains: the innermost
  ;; local binding isn't reported inside a deftest the way it is in a plain defn.)
  (let [values (enlighten-values
                '(clojure.test/deftest enl-deftest-sample
                   (let [a 42] (clojure.test/is (= 43 (+ a 1))))))]
    (is (contains? values "43")
        "a sub-expression inside the deftest body lights up")))
