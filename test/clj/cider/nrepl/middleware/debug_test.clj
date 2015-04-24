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

(deftest read-debug
  (reset! @#'d/debugger-message {})
  (with-redefs [t/send
                (fn [trans {:keys [key] :as msg}]
                  (reset! @#'d/debugger-input {key msg}))]
    (#'d/read-debug {:test true} :test-type "prompt")
    (is (empty? @@#'d/debugger-input)))
  (with-redefs [t/send
                (fn [trans {:keys [key] :as msg}]
                  (reset! @#'d/debugger-input (assoc msg key msg)))]
    (#'d/read-debug {:test true} :test-type "prompt")
    (are [pred key] (pred (key @@#'d/debugger-input))
      true? :test
      :need-debug-input :status
      string? :key
      #(= :test-type %) :input-type)))

(defn- send-override
  [value]
  (fn [trans {:keys [key]}]
    (reset! @#'d/debugger-input {key value})))

(deftest read-debug-command
  (reset! @#'d/debugger-message {})
  (with-redefs [t/send
                (fn [trans {:keys [key input-type]}]
                  (reset! @#'d/debugger-input {key (first input-type)}))]
    (is (= 'value (#'d/read-debug-command 'value {}))))
  (with-redefs [d/abort! (constantly :aborted)
                t/send
                (fn [trans {:keys [key input-type]}]
                  (reset! @#'d/debugger-input {key (last input-type)}))]
    (is (= :aborted (#'d/read-debug-command 'value {}))))

  (with-redefs [d/abort! (constantly :aborted)
                t/send (send-override :quit)]
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
  (reset! @#'d/debugger-message {})
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

;; (deftest breakpoint
;;   (reset! @#'d/debugger-message {})
;;   (let [x 1]
;;     (with-redefs [t/send (send-override '(inc 10))]
;;       (d/breakpoint x {})))
;;   (with-locals
;;     (with-redefs [t/send (send-override '(inc x))]
;;       (is (= 2 (#'d/read-debug-eval-expression "" {}))))))
