(ns cider.nrepl.middleware.debug-integration-test
  (:require [cider.nrepl.middleware.debug :as d]
            [cider.nrepl.test.server :refer [start-server]]
            [clojure.test :refer :all]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.server :as nrepl.server]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.java.io :as io])
  (:import java.util.UUID
           [java.util.concurrent TimeUnit LinkedBlockingQueue]))


;;; Helpers for starting an nRepl session
;;; We do not use clojure.tools.nrepl/client-session here because it
;;; is built with the expectation that each message sent to the server
;;; results in a response message. When that does not happen (as in
;;; the case of "init-debugger"), it blocks forever.
(def ^:dynamic *transport* nil)
(def ^:dynamic *session-id* nil)

(def ^:dynamic *dbg* false)

(defn- dbg [& args]
  (when *dbg*
    (locking #'*dbg*
      (apply prn args))))

(defn debug-handler []
  (nrepl.server/default-handler #'cider.nrepl/wrap-debug))

(defonce next-id
  (let [id (atom 0)]
    #(swap! id inc)))

(defn nrepl-send [msg]
  (let [msg (assoc msg
                   :session *session-id*
                   :id (next-id))]
    (dbg "==>" msg)
    (transport/send *transport* msg)))

(defn nrepl-recv []
  (let [msg (transport/recv *transport* 200)]
    (dbg "<==" msg)
    msg))

(defn with-nrepl-session*
  "Run the given function with *transport* and *session-id* bound."
  [f]
  (with-open [server    (start-server :handler (debug-handler))
              transport (nrepl/connect :port (:port server))]
    ;; Create a session by sending the "clone" op
    (transport/send transport {:op "clone" :id (next-id)})
    (binding [*transport*  transport
              *session-id* (:new-session (transport/recv transport 1000))]
      (f))))

(defmacro with-nrepl-session [& body]
  `(with-nrepl-session* (fn [] ~@body)))


;;; Helpers for initiating a new debugger session.

(def ^:dynamic *debugger-key*
  "Queue of :key values received in :debug-value messages."
  nil)

(defn current-key []
  (.poll *debugger-key* 200 TimeUnit/MILLISECONDS))

(defn with-debug-session* [f]
  ;; This is necessary because the debugger currenly uses some mutable
  ;; global state - there is a single def'ed var called
  ;; `debugger-message`, and this makes it impossible to start an
  ;; isolated, independent debugging session, which is what we want to
  ;; do in these tests Unfortunately, this means that you cannot use
  ;; the cider debugger to debug these tests. :(
  (with-redefs [d/debugger-message (atom nil)]
    (nrepl-send {:op "init-debugger"})
    (binding [*debugger-key* (LinkedBlockingQueue.)]
      (f))))

(defmacro with-debug-session [& body]
  `(with-debug-session* (fn [] ~@body)))


;;; Sort-of a DSL for testing nrepl interactions

(defmulti debugger-send (fn [op & _] op))

(defmethod debugger-send :eval [_ code]
  (nrepl-send {:op :eval :code code}))

(defmacro def-debug-op [op]
  `(defmethod debugger-send ~op [_#]
     (nrepl-send {:op :debug-input :input ~(str op) :key (current-key)})))

(def-debug-op :next)
(def-debug-op :continue)
(def-debug-op :in)

(defmethod debugger-send :out [_ & [force?]]
  (nrepl-send {:op :debug-input :input (str {:response :out :force? force?}) :key (current-key)}))

(defmethod debugger-send :here [_ coor & [force?]]
  (nrepl-send {:op :debug-input :input (str {:response :here :coord coor :force? force?}) :key (current-key)}))

(defmacro debugger-expect [expected]
  ;; This is a macro so that failed assertions will show at the right
  ;; line in the test.
  (let [msg-sym    (gensym "msg")
        assertions (for [[k v] expected]
                     `(is (= ~v (get ~msg-sym ~k)) (str "Message: " ~msg-sym)))]
    `(let [~msg-sym (nrepl-recv)]
       ~@assertions
       (when-let [k# (:key ~msg-sym)]
         (.put *debugger-key* k#))
       ~msg-sym)))

(def --> debugger-send)
(defmacro <-- [& body]
  `(debugger-expect ~@body))


;;; Tests

(use-fixtures :each
  (fn [f]
    (with-nrepl-session
      (with-debug-session
        (f)))))


(deftest debug-expression-test
  (testing "normal eval (no debugging)"
    (--> :eval "(+ 2 3)")
    (<-- {:value "5"})
    (<-- {:status ["done"]}))

  (testing "#break reader, no breakpoints"
    ;; This code has only literals and core functions, so it should
    ;; not break, but should just return the value
    (--> :eval "#break (+ 2 3)")
    (<-- {:value "5"})
    (<-- {:status ["done"]}))

  (testing "#dbg reader, with breaks"
    (--> :eval
        "#dbg
         (let [x 1]
           (inc x))")
    (<-- {:debug-value "1"})            ; x
    (--> :next)
    (<-- {:debug-value "2"})            ; (inc x)
    (--> :next)
    (<-- {:value "2"})                  ; (let ...)
    (<-- {:status ["done"]})))

(deftest debug-ops-test
  (--> :eval "(ns user.test.debug)")
  (<-- {:ns "user.test.debug"})
  (<-- {:status ["done"]})

  (--> :eval
       "#dbg
        (defn foo [x]
          (+ x (inc x)))")
  (<-- {:value "#'user.test.debug/foo"})
  (<-- {:status ["done"]})

  (testing "next"
    (--> :eval "(foo 1)")
    (<-- {:debug-value "1" :coor [3 1]}) ; x
    (--> :next)
    (<-- {:debug-value "1" :coor [3 2 1]}) ; x
    (--> :next)
    (<-- {:debug-value "2" :coor [3 2]}) ; (inc x)
    (--> :next)
    (<-- {:debug-value "3" :coor [3]})  ; (+ ...)
    (--> :next)
    (<-- {:value "3"})                  ; (foo 1)
    (<-- {:status ["done"]}))

  (testing "out"
    (--> :eval "(foo 1)")
    (<-- {:debug-value "1" :coor [3 1]})
    (--> :out)
    (<-- {:debug-value "3" :coor [3]})
    (--> :out)
    (<-- {:value "3"})
    (<-- {:status ["done"]}))

  (testing "continue"
    (--> :eval "(foo 1)")
    (<-- {:debug-value "1" :coor [3 1]})
    (--> :continue)
    (<-- {:value "3"})
    (<-- {:status ["done"]}))

  ;; To test the ":here" op properly, we need a slightly more
  ;; complicated function
  (--> :eval
       "#dbg
        (defn bar [x y]
          (let [z (+ x y)
                a (inc z)]
            (+ a (inc z))))")
  (<-- {:value "#'user.test.debug/bar"})
  (<-- {:status ["done"]})

  (testing "here"
    (--> :eval "(bar 1 2)")
    (<-- {:debug-value "1" :coor [3 1 1 1]}) ; the x in (let [z (+ x y)])
    ;; "here" op on the `a` in `(+ a ...)`
    (--> :here [3 2 1])
    (<-- {:debug-value "4" :coor [3 2 1]})
    (--> :continue)
    (<-- {:value "8"})
    (<-- {:status ["done"]})))

(deftest call-instrumented-fn-when-stepping-out-test
  ;; When we step out of a form, instrumented functions that are
  ;; called should still be debugged.

  (--> :eval "(ns user.test.debug)")
  (<-- {:ns "user.test.debug"})
  (<-- {:status ["done"]})

  ;; First, create an instrumented function
  (--> :eval
       "#dbg
        (defn foo [x]
          (+ 10 x))")
  (<-- {:value "#'user.test.debug/foo"})
  (<-- {:status ["done"]})

  ;; Then eval some code that calls the function
  (--> :eval
       "#dbg
        (let [i 0]
          (inc i)
          (foo i))")

  ;; 1) should break in the `let` form at `i` and `(inc i)`
  (<-- {:debug-value "0" :coor [2 1] :locals [["i" "0"]]})
  (--> :next)
  (<-- {:debug-value "1" :coor [2] :locals [["i" "0"]]})

  ;; 2) Step out, to skip stepping through the rest of the `let` form,
  ;; including the call to `foo`.
  (--> :out)

  ;; 3) The debugger should stop in `foo`
  (<-- {:debug-value "0" :coor [3 2] :locals [["x" "0"]]})
  (--> :next)
  (<-- {:debug-value "10" :coor [3] :locals [["x" "0"]]})
  (--> :next)

  ;; 4) return value of the `let`
  (<-- {:value "10"})
  (<-- {:status ["done"]}))

(deftest call-instrumented-fn-when-doing-here-op-test
  ;; When we jump ahead by doing a `:here`, instrumented functions
  ;; that are called from the skipped-over code should still be
  ;; debugged.

  (--> :eval "(ns user.test.debug)")
  (<-- {:ns "user.test.debug"})
  (<-- {:status ["done"]})

  ;; First, create an instrumented function
  (--> :eval
       "#dbg
        (defn foo [x]
          (+ 10 x))")
  (<-- {:value "#'user.test.debug/foo"})
  (<-- {:status ["done"]})

  ;; Then eval some code that calls the function
  (--> :eval "#dbg
              (let [i 7]
                (foo (inc i))
                (dec i))")

  ;; 1) should break at the `i` in `(foo (inc i))`
  (<-- {:debug-value "7" :coor [2 1 1] :locals [["i" "7"]]})
  
  ;; 2) Do a `:here` op at the end of `(dec i)`, skipping over the
  ;; call to foo.
  (--> :here [3])

  ;; 3) The debugger should stop in `foo`
  (<-- {:debug-value "8" :coor [3 2] :locals [["x" "8"]]})
  (--> :next)
  (<-- {:debug-value "18" :coor [3] :locals [["x" "8"]]})
  (--> :next)

  ;; 4) Then, once out of foo, should jump to the point where we did
  ;; the `:here` op.
  (<-- {:debug-value "6" :coor [3]})
  (--> :next)

  ;; 5) return value of the `let`
  (<-- {:value "6"})
  (<-- {:status ["done"]}))


;;; Tests for force-step operations

(deftest force-step-out-past-instrumented-fn-test
  ;; When we force-step out of a form, instrumented functions that are
  ;; called should not be debugged.

  (--> :eval "(ns user.test.debug)")
  (<-- {:ns "user.test.debug"})
  (<-- {:status ["done"]})

  ;; First, create an instrumented function
  (--> :eval
       "#dbg
        (defn foo [x]
          (+ 10 x))")
  (<-- {:value "#'user.test.debug/foo"})
  (<-- {:status ["done"]})

  ;; Then eval some code that calls the function
  (--> :eval
       "#dbg
        (let [i 4]
          (inc i)
          (foo i))")

  ;; 1) should break at the `i` and then the `(inc i)`
  (<-- {:debug-value "4" :coor [2 1] :locals [["i" "4"]]})
  (--> :next)
  (<-- {:debug-value "5" :coor [2] :locals [["i" "4"]]})

  ;; 2) Now force-step out, to skip stepping through the rest of the
  ;; `let` form, including the call to `foo`.
  (--> :out true)

  ;; 3) The debugger should not stop in `foo`, even though it is
  ;; instrumented, but should go straight to the value of the let
  ;; form.
  (<-- {:value "14"})
  (<-- {:status ["done"]}))

(deftest force-here-past-instrumented-fn-test
  ;; When we do force-here operation that jumps past a call to an
  ;; instrumented function, the function should not be debugged.

  (--> :eval "(ns user.test.debug)")
  (<-- {:ns "user.test.debug"})
  (<-- {:status ["done"]})

  ;; First, create an instrumented function
  (--> :eval
       "#dbg
        (defn foo [x]
          (+ 10 x))")
  (<-- {:value "#'user.test.debug/foo"})
  (<-- {:status ["done"]})

  ;; Then eval some code that calls the function
  (--> :eval
       "#dbg
        (let [i 5]
          (foo (inc i))
          (dec i))")

  ;; 1) should break at the `i` in `(foo (inc i))`
  (<-- {:debug-value "5" :coor [2 1 1] :locals [["i" "5"]]})

  ;; 2) Now do a force-here to `(dec i)`
  (--> :here [3] true)

  ;; 3) The debugger should not stop in `foo`, even though it is
  ;; instrumented, but should stop after `(dec i)`.
  (<-- {:debug-value "4" :coor [3]})
  (--> :next)
  
  (<-- {:value "4"})
  (<-- {:status ["done"]}))


;;; Tests for conditional breakpoints

(deftest conditional-in-for-test
  (--> :eval
       "(for [i (range 5)]
          #dbg ^{:break/when (= 2 i)}
          (inc i))")
  (<-- {:debug-value "2" :coor [2 1]}) ; i
  (--> :next)
  (<-- {:debug-value "3" :coor [2]}) ; (inc i)
  (--> :next)
  (<-- {:value "(1 2 3 4 5)"})              ; (for ...)
  (<-- {:status ["done"]}))

(deftest conditional-in-defn-test
  (--> :eval "(ns user.test.conditional-break)")
  (<-- {:ns "user.test.conditional-break"})
  (<-- {:status ["done"]})

  (--> :eval
       "(defn foo [x]
          #dbg ^{:break/when (odd? x)}
          (inc x))")
  (<-- {:value "#'user.test.conditional-break/foo"})
  (<-- {:status ["done"]})

  ;; Call foo 3 times, but it should only break once, when the
  ;; argument is odd.
  (--> :eval
       "(for [i (range 3)]
          (foo i))")
  (<-- {:debug-value "1" :coor [3 1] :locals [["x" "1"]]})
  (--> :next)
  (<-- {:debug-value "2" :coor [3]})
  (--> :next)
  (<-- {:value "(1 2 3)"})
  (<-- {:status ["done"]}))

(deftest call-instrumented-fn-from-skipped-break-test
  ;; When breaks are skipped due to a false conditional, instrumented
  ;; functions called from withing the skipped form should still be
  ;; debugged.

  (--> :eval "(ns user.test.conditional-break)")
  (<-- {:ns "user.test.conditional-break"})
  (<-- {:status ["done"]})

  ;; First, create an instrumented function
  (--> :eval
       "#dbg
        (defn foo [x]
          (inc x))")
  (<-- {:value "#'user.test.conditional-break/foo"})
  (<-- {:status ["done"]})

  ;; Then call the function from code with a conditional breakpoint
  (--> :eval
       "(for [i [1 2]]
          #dbg ^{:break/when (even? i)}
          (foo i))")

  ;; 1) should break in `foo` at `x` and `(inc x)`
  (<-- {:debug-value "1" :coor [3 1] :locals [["x" "1"]]})
  (--> :next)
  (<-- {:debug-value "2" :coor [3] :locals [["x" "1"]]})
  (--> :next)

  ;; 2) then, should break in the for when i is 2
  (<-- {:debug-value "2" :coor [2 1] :locals [["i" "2"]]})
  (--> :next)

  ;; 3) then step into `foo` again
  (<-- {:debug-value "2" :coor [3 1] :locals [["x" "2"]]})
  (--> :next)
  (<-- {:debug-value "3" :coor [3] :locals [["x" "2"]]})
  (--> :next)

  ;; 4) final breakpoint, on the return value of `(foo 2)`
  (<-- {:debug-value "3" :coor [2] :locals [["i" "2"]]})
  (--> :next)

  ;; and done
  (<-- {:value "(2 3)"})
  (<-- {:status ["done"]}))

(deftest step-in-to-function-in-current-project-test
  ;; We want to ensure that the debugger can find and instrument the code of a
  ;; function that lives in a regular file on the classpath (as opposed to,
  ;; for example, in a jar, which is tested later).
  (--> :eval "(ns user.test.step-in
                (:require [cider.nrepl.middleware.debug-integration-test.fn :as test-fn]))")
  (<-- {:ns "user.test.step-in"})
  (<-- {:status ["done"]})

  (--> :eval
       "#dbg
        (defn foo [s]
          (test-fn/as-sym s))")
  (<-- {:value "#'user.test.step-in/foo"})
  (<-- {:status ["done"]})

  (testing "step in"
    (--> :eval "(foo \"bar\")")
    (<-- {:debug-value "\"bar\""})
    (--> :in)

    ;; Note - if changing test/src/cider/nrepl/middleware/debug_integration_test/fn.clj, also change this:
    (let [msg (<-- {:line        7
                    :column      0
                    :debug-value "\"bar\""
                    :coor        [3 1 1]
                    :locals      [["x" "\"bar\""]]})
          file (:file msg)]
      (is (.endsWith file "/cider/nrepl/middleware/debug_integration_test/fn.clj"))
      (is (.startsWith file "file:/"))

      ;; Step out a couple of times, taking us out of test-fn/as-sym
      (--> :out)
      (<-- {:debug-value "false" :file file})
      (--> :out)
      (<-- {:debug-value "bar" :file file})

      ;; Next step should take us back into foo
      (--> :next)
      (<-- {:debug-value "bar"})
      (--> :next)
      (<-- {:value "bar"})
      (<-- {:status ["done"]})))

  (testing ":next command should not step in"
    (--> :eval "(foo \"bar\")")
    (<-- {:debug-value "\"bar\""})
    (--> :next)
    (<-- {:debug-value "bar" :coor [3]}) ; return value of (test-fn/as-sym ...)
    (--> :continue)
    (<-- {:value "bar"})
    (<-- {:status ["done"]}))

  (testing "stepped-in-to functions are not instrumented"
    ;; Test that stepped-in-to functions are not instrumented - that is,
    ;; calling test-fn/as-sym directly now should not invoke the debugger,
    ;; even though we were stepping through its code in the test above.
    (--> :eval "(test-fn/as-sym \"bar\")")
    (<-- {:value "bar"})
    (<-- {:status ["done"]}))

  (testing ":in command acts as :next on non-function"
    (--> :eval "#dbg (let [s \"blah\"]
                       s
                       (test-fn/as-sym s))")
    (<-- {:debug-value "\"blah\"" :coor [2]})
    ;; try to step in to `s`, make sure nothing blows up
    (--> :in)
    (<-- {:debug-value "\"blah\"" :coor [3 1]})
    (--> :continue)
    (<-- {:value "blah"})
    (<-- {:status ["done"]})))

(deftest step-in-to-function-in-jar-test
  ;; Step into clojure.tools.nrepl.server/handle*. To do this, we need to find
  ;; and instrument the source, which is in a jar file. Note that this function
  ;; is used because it is not marked as a :source-dep, so we can rely on the
  ;; namespace remaining unmunged, which is important when these tests run on
  ;; travis CI.
  (--> :eval "(ns user.test.step-in
                (:require [clojure.tools.nrepl.server :as server]))")
  (<-- {:ns "user.test.step-in"})
  (<-- {:status ["done"]})

  (--> :eval
       "#dbg
        (defn foo [m]
          (server/handle* m identity 23))")
  (<-- {:value "#'user.test.step-in/foo"})
  (<-- {:status ["done"]})

  (--> :eval "(foo {})")
  (<-- {:debug-value "{}" :coor [3 1]})
  (--> :in)

  (let [msg  (<-- {:debug-value "{}"
                   :coor [3 1 1 1]})
        file (:file msg)]
    (.startsWith file "jar:file:")
    (.endsWith file "/clojure/tools/nrepl/server.clj"))

  (--> :continue)
  (<-- {:value "{:transport 23}"})
  (<-- {:status ["done"]}))
