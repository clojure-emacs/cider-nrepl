(ns ^:debugger cider.nrepl.middleware.enlighten-test
  (:require
   [cider.nrepl.middleware.debug :as d]
   [cider.nrepl.middleware.enlighten :as e]
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
    (is (some? msg) "an enlighten value was sent")
    (is (= #{:enlighten} (:status msg)))
    (is (= 42 (:line msg)) "positioned by the form's line, not the eval's")))

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
