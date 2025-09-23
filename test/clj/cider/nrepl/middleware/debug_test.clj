(ns ^:debugger cider.nrepl.middleware.debug-test
  (:require
   [cider.nrepl.middleware.util.instrument :as ins]
   [cider.nrepl.middleware.debug  :as d]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [clojure.walk :as walk]
   [matcher-combinators.matchers :as matchers]
   [nrepl.middleware.interruptible-eval :refer [*msg*]]
   [nrepl.transport :as t]))

(def ^:const bfkey :cider.nrepl.middleware.util.instrument/breakfunction)

;; dummy STATE__
(defn STATE []
  {:msg {:original-ns "user"}
   :session-id (atom nil)
   :skip (atom false)})

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

    (let [code "(foo (bar blah x))"
          state (assoc-in (STATE) [:msg :code] code)]
      (#'d/skip-breaks! :deeper [1 2] code nil)
      (is (d/skip-breaks? [] state))
      (is (not (#'d/skip-breaks? [1 2] state)))
      (is (not (#'d/skip-breaks? [2 2 1] state)))
      (is (#'d/skip-breaks? [1 2 3] state)))))

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
  `~(#'d/locals-capturer &env))

(defmacro add-locals
  "Add locals to map m."
  [& [m]]
  `(assoc ~m
          :locals ~(#'d/locals-capturer &env)
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
    (is (= :aborted (#'d/read-debug-command nil 'value (locals) (STATE)))))
  (with-redefs [t/send (send-override :next)]
    (is (= 'value (#'d/read-debug-command nil 'value (locals) (STATE)))))
  (binding [*msg* {:session (atom {})}
            d/*skip-breaks* (atom {:mode :all})]
    (with-redefs [t/send (send-override :continue)]
      (is (= 'value (#'d/read-debug-command nil 'value (locals) (STATE))))
      (is (#'d/skip-breaks? nil (STATE)))))
  (binding [*msg* {:session (atom {})}
            d/*skip-breaks* (atom {:mode :all})]
    (with-redefs [t/send (send-override :out)]
      (is (= 'value (#'d/read-debug-command [1 2 3] 'value (locals) (STATE))))
      (is (#'d/skip-breaks? [1 2 3] (STATE)))
      (is (#'d/skip-breaks? [1 2 4] (STATE)))
      (is (not (#'d/skip-breaks? [1 2] (STATE))))))
  (with-redefs [t/send (send-override :inject)]
    (is (= :inject (#'d/read-debug-command nil 'value (locals) (STATE))))))

(deftest read-debug-command-eval-test
  (let [replies (atom [:eval 100 :next])]
    (with-redefs [t/send (fn [trans {:keys [key]}]
                           (deliver (@d/promises key) (str (first @replies)))
                           (swap! replies rest))]
      (is (= 'value (#'d/read-debug-command nil 'value (locals) (STATE)))))))

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
  (reset! d/print-options {:length 4, :level 2})
  (is (< (count (d/pr-short [1 2 3 4 5 6 7 8 9 10]))
         (count (pr-str [1 2 3 4 5 6 7 8 9 10]))))
  (is (< (count (d/pr-short [[[1 2 3 4]]]))
         (count (pr-str [[[1 2 3 4]]]))))
  (is (= (d/pr-short [1 2 3 4])
         (pr-str [1 2 3 4])))
  (reset! d/print-options {})
  (is (= 2003 (count (d/pr-short (range))))))

(deftest breakpoint
  ;; Map merging
  (let [capture (atom nil)]
    (with-redefs [d/read-debug-command (fn [_ v _ s]
                                         (reset! capture (assoc (:msg s) :value v))
                                         v)
                  d/debugger-message   (atom [:fake])
                  d/*skip-breaks*      (atom nil)]
      (with-bindings {#'d/*top-level-form-meta*
                      {::d/form-info {:code        :code
                                      :file        :file
                                      :original-id :id}
                       :line         :line
                       :column       :column}}
        (let [form `(d/with-initial-debug-bindings
                      (d/breakpoint-if-interesting (inc 10) {:coor [6]} ~'(inc 10)))
              m (eval form)]
          (is+ {:value       11
                :file        :file
                :line        :line
                :column      :column
                :code        :code
                :original-id :id}
               @capture))
        (reset! d/debugger-message [:fake])
        ;; Locals capturing
        (eval `(d/with-initial-debug-bindings
                 (let [~'x 10]
                   (d/breakpoint-if-interesting
                    (locals) {:coor [1]} nil))))
        (is (= (:value @capture) '{x 10}))
        ;; Top-level sexps are not debugged, just returned.
        (is (= (eval `(d/with-initial-debug-bindings
                        (let [~'x 10]
                          (d/breakpoint-if-interesting
                           (locals)
                           {:coor []}
                           nil))))
               '{x 10}))))))

(deftest instrumentation-stress-test
  (testing "able to compile this function full of locals"
    (is
     (-> '(defn a-fn [a0]
            (let [a0 (long (+))
                  a1 (long (+ a0))
                  a2 (+ a0 a1)
                  a3 (+ a0 a1 a2)
                  a4 (+ a0 a1 a2 a3)
                  a5 (+ a0 a1 a2 a3 a4)
                  a6 (+ a0 a1 a2 a3 a4 a5)
                  a7 (+ a0 a1 a2 a3 a4 a5 a6)
                  a8 (+ a0 a1 a2 a3 a4 a5 a6 a7)
                  a9 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8)
                  a10 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9)
                  a11 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10)
                  a12 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11)
                  a13 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12)
                  a14 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13)
                  a15 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14)
                  a16 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15)
                  a17 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16)
                  a18 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17)
                  a19 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18)
                  a20 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19)
                  a21 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20)
                  a22 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21)
                  a23 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22)
                  a24 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23)
                  a25 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24)
                  a26 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24 a25)]
              a0))
         d/debug-reader
         ins/instrument-tagged-code
         eval)))

  (testing "fails if there is one extra line"
    (is (thrown? clojure.lang.Compiler$CompilerException
                 (-> '(defn a-fn [a0]
                        (let [a0 (long (+))
                              a1 (long (+ a0))
                              a2 (+ a0 a1)
                              a3 (+ a0 a1 a2)
                              a4 (+ a0 a1 a2 a3)
                              a5 (+ a0 a1 a2 a3 a4)
                              a6 (+ a0 a1 a2 a3 a4 a5)
                              a7 (+ a0 a1 a2 a3 a4 a5 a6)
                              a8 (+ a0 a1 a2 a3 a4 a5 a6 a7)
                              a9 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8)
                              a10 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9)
                              a11 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10)
                              a12 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11)
                              a13 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12)
                              a14 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13)
                              a15 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14)
                              a16 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15)
                              a17 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16)
                              a18 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17)
                              a19 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18)
                              a20 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19)
                              a21 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20)
                              a22 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21)
                              a23 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22)
                              a24 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23)
                              a25 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24)
                              a26 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24 a25)
                              a27 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24 a25 a26)
                              a28 (+ a0 a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22 a23 a24 a25 a26 a27)]
                          a0))
                     d/debug-reader
                     ins/instrument-tagged-code
                     eval)))))
