(ns cider.nrepl.middleware.debug-test
  (:require [clojure.test :refer :all]
            [nrepl.middleware.interruptible-eval :refer [*msg*]]
            [nrepl.transport :as t]
            [cider.nrepl.middleware.util.instrument :as ins]
            [cider.nrepl.middleware.debug  :as d]
            [clojure.walk :as walk]))

(def ^:const bfkey :cider.nrepl.middleware.util.instrument/breakfunction)

(deftest irrelevant-return-value-test
  (are [x] (let [exp (clojure.walk/macroexpand-all x)]
             (= exp (clojure.walk/macroexpand-all `(d/breakpoint-if-interesting ~exp [] nil))))
    '(defn name "" [] (inc 2))
    '(defn- name "" [] (inc 2))
    '(def name "")
    '(fn name [] (inc 2))
    '(fn* name ([] (inc 2)))))

(deftest coord<-test
  (are [a b] (and (ins/coord< a b)
                  (not (ins/coord< b a)))
    [1] []
    [0] [1]
    [1] [2]
    [1 2] [1 3]
    [1 0] [1]))

(deftest skip-breaks-test
  (binding [d/*skip-breaks* (atom {:mode :all})]
    (is (#'d/skip-breaks? [] nil))
    (is (#'d/skip-breaks? nil nil))
    (is (#'d/skip-breaks? [1 2] nil))
    (is (#'d/skip-breaks? [2 2 1] nil))

    (#'d/skip-breaks! nil)
    (is (#'d/skip-breaks? [] nil))
    (is (#'d/skip-breaks? nil nil))
    (is (not (#'d/skip-breaks? [1 2] nil)))
    (is (not (#'d/skip-breaks? [2 2 1] nil)))

    (let [code "(foo (bar blah x))"]
      (#'d/skip-breaks! :deeper [1 2] code nil)
      (is (d/skip-breaks? [] code))
      (is (not (#'d/skip-breaks? [1 2] code)))
      (is (not (#'d/skip-breaks? [2 2 1] code)))
      (is (#'d/skip-breaks? [1 2 3] code)))))

(defn- send-override
  [value]
  (fn [trans {:keys [key]}]
    (deliver (@d/promises key) (str value))))

(defn- send-override-msg
  [trans {:keys [key] :as msg}]
  (let [pro (@d/promises key)]
    (swap! d/promises dissoc key)
    (deliver pro (str msg))))

(defmacro extras
  "Add :original-ns to map m."
  [& [m]]
  `(merge {:original-ns "user"}
          ~m))

(defmacro locals []
  `~(#'d/sanitize-env &env))

(defmacro add-locals
  "Add locals to map m."
  [& [m]]
  `(assoc ~m
          :locals ~(#'d/sanitize-env &env)
          :original-ns "user"))

(deftest read-debug-input-roundtrip
  (reset! d/promises {})
  (with-redefs [t/send send-override-msg]
    (is (:test (#'d/read-debug-input (extras {:test true}) :test-type "prompt")))
    (is (empty? @d/promises))
    (are [pred key] (pred (key (#'d/read-debug-input (extras {:test true}) :test-type "prompt")))
      true? :test
      :need-debug-input :status
      string? :key
      #(= :test-type %) :input-type)))

(deftest read-debug-command
  (reset! @#'d/debugger-message {})

  ;; Check functionality
  (with-redefs [d/abort! (constantly :aborted)
                t/send   (send-override :quit)]
    (is (= :aborted (#'d/read-debug-command 'value (add-locals {})))))
  (with-redefs [t/send (send-override :next)]
    (is (= 'value (#'d/read-debug-command 'value (add-locals {})))))
  (binding [*msg* {:session (atom {})}
            d/*skip-breaks* (atom {:mode :all})]
    (with-redefs [t/send (send-override :continue)]
      (is (= 'value (#'d/read-debug-command 'value (add-locals {}))))
      (is (#'d/skip-breaks? nil nil))))
  (binding [*msg* {:session (atom {})}
            d/*skip-breaks* (atom {:mode :all})]
    (with-redefs [t/send (send-override :out)]
      (is (= 'value (#'d/read-debug-command 'value (add-locals {:coor [1 2 3]}))))
      (is (#'d/skip-breaks? [1 2 3] nil))
      (is (#'d/skip-breaks? [1 2 4] nil))
      (is (not (#'d/skip-breaks? [1 2] nil)))))
  (with-redefs [t/send (send-override :inject)]
    (is (= :inject (#'d/read-debug-command 'value (add-locals {}))))))

(deftest read-debug-command-eval-test
  (let [replies (atom [:eval 100 :next])]
    (with-redefs [t/send (fn [trans {:keys [key]}]
                           (deliver (@d/promises key) (str (first @replies)))
                           (swap! replies rest))]
      (is (= 'value (#'d/read-debug-command 'value (add-locals {})))))))

(deftest read-eval-expression-test
  (reset! d/debugger-message {})
  (let [x 1]
    (with-redefs [t/send (send-override '(inc 10))]
      (is (= 11 (#'d/read-eval-expression "" (add-locals {})))))
    (with-redefs [t/send (send-override '(inc x))]
      (is (= 2 (#'d/read-eval-expression "" (add-locals {})))))))

(deftest eval-add-locals
  (reset! @#'d/debugger-message {})
  (is (= 2
         (let [x 1]
           (#'d/eval-with-locals '(inc x) (add-locals {}))))))

(deftest initialize-test
  (with-redefs [d/debugger-message (atom nil)]
    (let [resp (atom nil)]
      (with-redefs [t/send (fn [_ response] (reset! resp response))]
        (#'d/initialize {:hi true}))
      (is (= @d/debugger-message {:hi true}))
      (is (not (:status @resp))))
    (let [resp (atom nil)]
      (with-redefs [t/send (fn [_ response] (reset! resp response))]
        (#'d/initialize {:hi true}))
      (is (:status @resp)))))

(deftest locals-for-message-test
  (let [x 1
        to__ignore 0]
    (is (= '(("x" "1"))
           (#'d/locals-for-message (locals))))))

(deftest eval-expression-with-code-test
  (is (= (#'d/read-eval-expression
          "Unused prompt" (add-locals {:some "random", 'meaningless :map}) '(inc 1))
         2))
  (let [x 10]
    (is (= (#'d/read-eval-expression
            "Unused prompt" (add-locals {:some "random", 'meaningless :map}) '(inc x))
           11))))

(deftest debug-reader-test
  (is (empty? (remove #(bfkey (meta %))
                      (d/debug-reader '[a b c]))))
  (is (bfkey (meta (d/debug-reader '[a b c]))))
  (is (= (count (remove #(bfkey (meta %))
                        (d/debug-reader '[a :b 10])))
         2)))

(deftest breakpoint-reader-test
  (is (bfkey (meta (d/breakpoint-reader '[a b c]))))
  (is (= '[a :b 10 "ok"]
         (remove #(bfkey (meta %)) (d/breakpoint-reader '[a :b 10 "ok"]))))
  ;; Just don't error
  (is (map d/breakpoint-reader '[a :b 10 "ok"])))

(deftest reader-macros-test
  (binding [*data-readers* {'dbg d/debug-reader}]
    ;; Reader macro variants
    (is (empty? (remove #(bfkey (meta %)) (read-string "#dbg [a b c]"))))
    (is (bfkey (meta (read-string "#dbg [a b c]"))))
    (is (= (count (remove #(bfkey (meta %)) (read-string "#dbg [a :b 10]")))
           2))))

(deftest pr-short-test
  (reset! d/print-length 4)
  (reset! d/print-level 2)
  (is (< (count (d/pr-short [1 2 3 4 5 6 7 8 9 10]))
         (count (pr-str [1 2 3 4 5 6 7 8 9 10]))))
  (is (< (count (d/pr-short [[[1 2 3 4]]]))
         (count (pr-str [[[1 2 3 4]]]))))
  (is (= (d/pr-short [1 2 3 4])
         (pr-str [1 2 3 4]))))

(deftest breakpoint
  ;; Map merging
  (with-redefs [d/read-debug-command (fn [v e] (assoc e :value v))
                d/debugger-message   (atom [:fake])
                d/*skip-breaks*      (atom nil)]
    (binding [*msg* {:session (atom {})
                     :code    :code
                     :id      :id
                     :file    :file
                     :line    :line
                     :column  :column}]
      (let [m (eval `(d/with-initial-debug-bindings
                       (d/breakpoint-if-interesting (inc 10) {:coor [6]} ~'(inc 10))))]
        (are [k v] (= (k m) v)
          :value       11
          :debug-value "11"
          :coor        [6]
          :file        :file
          :line        :line
          :column      :column
          :code        :code
          :original-id :id))
      (reset! d/debugger-message [:fake])
      ;; Locals capturing
      (is (= (:value (eval `(d/with-initial-debug-bindings
                              (let [~'x 10] (d/breakpoint-if-interesting
                                             (locals)
                                             {:coor [1]} nil)))))
             '{x 10}))
      ;; Top-level sexps are not debugged, just returned.
      (is (= (eval `(d/with-initial-debug-bindings
                      (let [~'x 10] (d/breakpoint-if-interesting
                                     (locals)
                                     {:coor []}
                                     nil))))
             '{x 10})))))
