(ns ^:debugger cider.nrepl.middleware.enlighten-test
  (:require
   [cider.nrepl.middleware.debug :as d]
   [cider.nrepl.middleware.enlighten :as e]
   [clojure.test :refer [deftest is]]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.transport :as t]))

(deftest eval-with-enlighten-uses-per-form-line
  ;; The enlighten overlay must be placed by the form's own line, not by the
  ;; line of the whole evaluation - that is what makes it work form-by-form
  ;; through `load-file`, where every form shares the same eval-level line.
  (let [sent (atom [])]
    (with-redefs [d/debugger-message (atom {:transport :fake :id "ID" :session "S"})
                  t/send (fn [_ msg] (swap! sent conj msg))]
      (binding [*msg* {:file "x.clj" :id "ID" :line 1 :column 1}]
        ;; the eval-level line is 1, but the form claims to live on line 42
        (let [v (e/eval-with-enlighten
                 (with-meta '(defn enl-sample [] (+ 1 2)) {:line 42 :column 5}))]
          (v))) ; running it fires the enlighten return-value message
      (let [enlighten-msg (->> @sent (filter :debug-value) first)]
        (is (some? enlighten-msg) "an enlighten value was sent")
        (is (= #{:enlighten} (:status enlighten-msg)))
        (is (= 42 (:line enlighten-msg))
            "positioned by the form's line, not the eval's")))))
