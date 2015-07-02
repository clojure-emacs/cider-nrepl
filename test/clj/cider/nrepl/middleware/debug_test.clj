(ns cider.nrepl.middleware.debug-test
  (:require [clojure.test :refer :all]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [clojure.tools.nrepl.transport :as t]
            [cider.nrepl.middleware.debug  :as d]))

(deftest skip-breaks
  (binding [*msg* {:session (atom {#'d/*skip-breaks* true})}]
    (is (#'d/skip-breaks? []))
    (is (#'d/skip-breaks? nil))

    (#'d/skip-breaks! false)
    (is (not (#'d/skip-breaks? [])))
    (is (not (#'d/skip-breaks? nil)))

    (#'d/skip-breaks! [1 2])
    (is (not (#'d/skip-breaks? [])))
    (is (not (#'d/skip-breaks? [1 2])))
    (is (not (#'d/skip-breaks? [2 2 1])))
    (is (#'d/skip-breaks? [1 2 3]))))

(defn- send-override-msg
  [trans {:keys [key] :as msg}]
  (let [pro (@d/promises key)]
    (swap! d/promises dissoc key)
    (deliver pro msg)))

(deftest read-debug
  (reset! d/promises {})
  (with-redefs [t/send send-override-msg]
    (is (:test (#'d/read-debug {:test true} :test-type "prompt")))
    (is (empty? @d/promises))
    (are [pred key] (pred (key (#'d/read-debug {:test true} :test-type "prompt")))
      true? :test
      :need-debug-input :status
      string? :key
      #(= :test-type %) :input-type)))

(defn- send-override
  [value]
  (fn [trans {:keys [key]}]
    (deliver (@d/promises key) value)))

(deftest read-debug-command
  (reset! @#'d/debugger-message {})
  ;; Check that :next is the first
  (with-redefs [t/send (fn [trans {:keys [key input-type]}]
                         (deliver (@d/promises key) (first input-type)))]
    (is (= 'value (#'d/read-debug-command 'value {}))))

  ;; Check that :quit is the last
  (with-redefs [d/abort! (constantly :aborted)
                t/send (fn [trans {:keys [key input-type]}]
                         (deliver (@d/promises key) (last input-type)))]
    (is (= :aborted (#'d/read-debug-command 'value {}))))

  ;; Check functionality
  (with-redefs [d/abort! (constantly :aborted)
                t/send   (send-override :quit)]
    (is (= :aborted (#'d/read-debug-command 'value {}))))
  (with-redefs [t/send (send-override :next)]
    (is (= 'value (#'d/read-debug-command 'value {}))))
  (binding [*msg* {:session (atom {#'d/*skip-breaks* true})}]
    (with-redefs [t/send (send-override :continue)]
      (is (= 'value (#'d/read-debug-command 'value {})))
      (is (#'d/skip-breaks? nil))))
  (binding [*msg* {:session (atom {#'d/*skip-breaks* true})}]
    (with-redefs [t/send (send-override :out)]
      (is (= 'value (#'d/read-debug-command 'value {:coor [1 2 3]})))
      (is (#'d/skip-breaks? [1 2 3]))
      (is (#'d/skip-breaks? [1 2 4]))
      (is (not (#'d/skip-breaks? [1 2])))))
  (with-redefs [t/send (send-override :inject)]
    (is (= :inject (#'d/read-debug-command 'value {})))))

(defmacro with-locals
  "Send value and coordinates to the client through the debug channel.
  Sends a response to the message stored in debugger-message."
  [value]
  `(binding [d/*locals* ~(#'d/sanitize-env &env)]
     ~value))

(deftest read-debug-eval-expression
  (reset! d/debugger-message {})
  (let [x 1]
    (with-redefs [t/send (send-override '(inc 10))]
      (is (= 11 (#'d/read-debug-eval-expression "" {}))))
    (with-locals
      (with-redefs [t/send (send-override '(inc x))]
        (is (= 2 (#'d/read-debug-eval-expression "" {})))))))

(deftest eval-with-locals
  (reset! @#'d/debugger-message {})
  (is (= 2
         (let [x 1]
           (with-locals
             (#'d/eval-with-locals '(inc x)))))))

(deftest eval-with-locals-exceptions
  (binding [*msg* {:session (atom {})}]
    (let [resp (atom nil)
          e (Exception. "HI")]
      (with-redefs [clojure.main/repl-caught (fn [& _])
                    t/send (fn [_ response] (reset! resp response))]
        (with-locals
          (is (not (#'d/eval-with-locals '(do (throw e) true))))))
      (are [k v] (= (k @resp) v)
        :status #{:eval-error}
        :ex (-> e class str)
        :root-ex (-> (#'clojure.main/root-cause e) class str)))))

(deftest initialize
  (reset! d/debugger-message nil)
  (let [resp (atom nil)]
    (with-redefs [t/send (fn [_ response] (reset! resp response))]
      (#'d/initialize {:hi true}))
    (is (= @d/debugger-message {:hi true}))
    (is (not (:status @resp))))
  (let [resp (atom nil)]
    (with-redefs [t/send (fn [_ response] (reset! resp response))]
      (#'d/initialize {:hi true}))
    (is (:status @resp))))

(deftest locals-for-message
  (let [x 1
        to_ignore 0
        to__ignore 0]
    (with-locals
      (is (= '(("x" "1"))
             (#'d/locals-for-message d/*locals*))))))

(deftest eval-expression-with-code
  (with-locals
    (is (= (#'d/read-debug-eval-expression
            "Unused prompt" {:some "random", 'meaningless :map} '(inc 1))
           2)))
  (let [x 10]
    (with-locals
      (is (= (#'d/read-debug-eval-expression
              "Unused prompt" {:some "random", 'meaningless :map} '(inc x))
             11)))))

(deftest inspect-then-read-command
  (binding [*msg* {:session (atom {})}]
    (with-redefs [d/read-debug-command vector]
      (let [[v m] (#'d/inspect-then-read-command {} 10)]
        (is (= v 10))
        (is (string? (:inspect m)))))))

(deftest debug-reader
  (is (empty? (remove #(:cider-breakfunction (meta %))
                      (d/debug-reader '[a b c]))))
  (is (:cider-breakfunction (meta (d/debug-reader '[a b c]))))
  (is (= (count (remove #(:cider-breakfunction (meta %))
                        (d/debug-reader '[a :b 10])))
         2)))

(deftest breakpoint-reader
  (is (:cider-breakfunction (meta (d/breakpoint-reader '[a b c]))))
  (is (= '[a :b 10 "ok"]
         (remove #(:cider-breakfunction (meta %)) (d/breakpoint-reader '[a :b 10 "ok"]))))
  ;; Just don't error
  (is (map d/breakpoint-reader '[a :b 10 "ok"])))

(deftest reader-macros
  (binding [*data-readers* {'dbg d/debug-reader}]
    ;; Reader macro variants
    (is (empty? (remove #(:cider-breakfunction (meta %)) (read-string "#dbg [a b c]"))))
    (is (:cider-breakfunction (meta (read-string "#dbg [a b c]"))))
    (is (= (count (remove #(:cider-breakfunction (meta %)) (read-string "#dbg [a :b 10]")))
           2))))
