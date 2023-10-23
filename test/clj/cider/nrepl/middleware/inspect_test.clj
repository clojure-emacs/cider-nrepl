(ns cider.nrepl.middleware.inspect-test
  (:require
   [cider.nrepl.middleware.inspect :as i]
   [cider.nrepl.test-session :as session]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.test :refer :all]))

(def inspect-tap-current-value-test-atom (atom nil))

(defn set-inspect-tap-current-value-test-atom-fn [x]
  (reset! inspect-tap-current-value-test-atom x))

(defn inspect-tap-current-value-test-fixture [f]
  (add-tap set-inspect-tap-current-value-test-atom-fn)
  (try
    (f)
    (finally
      (reset! inspect-tap-current-value-test-atom nil)
      (remove-tap set-inspect-tap-current-value-test-atom-fn))))

(use-fixtures :each session/session-fixture inspect-tap-current-value-test-fixture)

(def datafy?
  (some? (resolve 'clojure.core.protocols/datafy)))

(def nil-result
  '["nil" (:newline)])

(def var-result
  (cond-> '("Class"
            ": " (:value "clojure.lang.Var" 0)
            (:newline)
            "Value: " (:value "true" 1)
            (:newline)
            (:newline)
            "--- Meta Information:"
            (:newline)
            "  " (:value ":ns" 2) " = " (:value "clojure.core" 3)
            (:newline)
            "  " (:value ":name" 4) " = " (:value "*assert*" 5)
            (:newline))
    datafy? (concat '((:newline)
                      "--- Datafy:"
                      (:newline)
                      "  " "0" ". " (:value "true" 6) (:newline)))))

(def code "(sorted-map :a {:b 1} :c \"a\" :d 'e :f [2 3])")

(def inspect-result
  '("Class"
    ": " (:value "clojure.lang.PersistentTreeMap" 0)
    (:newline)
    (:newline)
    "--- Contents:"
    (:newline)
    "  " (:value ":a" 1) " = " (:value "{ :b 1 }" 2)
    (:newline)
    "  " (:value ":c" 3) " = " (:value "\"a\"" 4)
    (:newline)
    "  " (:value ":d" 5) " = " (:value "e" 6)
    (:newline)
    "  " (:value ":f" 7) " = " (:value "[ 2 3 ]" 8)
    (:newline)))

(def push-result
  '("Class"
    ": " (:value "clojure.lang.PersistentArrayMap" 0)
    (:newline)
    (:newline)
    "--- Contents:"
    (:newline)
    "  " (:value ":b" 1) " = " (:value "1" 2)
    (:newline)
    (:newline)
    "--- Path:"
    (:newline)
    "  " ":a"))

(def next-page-result
  '("Class"
    ": " (:value "clojure.lang.LazySeq" 0)
    (:newline)
    (:newline)
    "--- Contents:"
    (:newline)
    "  " "..."
    (:newline)
    "  " "32" ". " (:value "32" 1)
    (:newline)
    "  " "33" ". " (:value "33" 2)
    (:newline)
    "  " "34" ". " (:value "34" 3)
    (:newline)
    (:newline)
    "--- Page Info:"
    (:newline)
    "  " "Page size: 32, showing page: 2 of 2"))

(def first-page-result
  '("Class"
    ": " (:value "clojure.lang.LazySeq" 0)
    (:newline)
    (:newline)
    "--- Contents:"
    (:newline)
    "  " "0" ". " (:value "0" 1)
    (:newline)
    "  " "1" ". " (:value "1" 2)
    (:newline)
    "  " "2" ". " (:value "2" 3)
    (:newline)
    "  " "3" ". " (:value "3" 4)
    (:newline)
    "  " "4" ". " (:value "4" 5)
    (:newline)
    "  " "..."
    (:newline)
    (:newline)
    "--- Page Info:"
    (:newline)
    "  " "Page size: 5, showing page: 1 of ?"))

(defn value [{:keys [value]}]
  (edn/read-string (first value)))

;; integration tests

(deftest nil-integration-test
  (testing "nil renders correctly"
    (is (= nil-result
           (value (session/message {:op      "eval"
                                    :inspect "true"
                                    :code    "nil"}))))))

(deftest pop-empty-integration-test
  (testing "popping an empty inspector renders nil"
    (is (= nil-result
           (value (session/message {:op "inspect-pop"}))))))

(deftest pop-empty-idempotent-integration-test
  (testing "popping an empty inspector is idempotent"
    (is (= nil-result
           (value (do
                    (session/message {:op "inspect-pop"})
                    (session/message {:op "inspect-pop"})))))))

(deftest push-empty-integration-test
  (testing "pushing an empty inspector index renders nil"
    (is (= nil-result
           (value (session/message {:op  "inspect-push"
                                    :idx 1}))))))

(deftest push-empty-idempotent-integration-test
  (testing "pushing an empty inspector index is idempotent"
    (is (= nil-result
           (value (do
                    (session/message {:op  "inspect-push"
                                      :idx 1})
                    (session/message {:op  "inspect-push"
                                      :idx 1})))))))

(deftest refresh-empty-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is (= nil-result
           (value (session/message {:op "inspect-refresh"}))))))

(deftest refresh-empty-idempotent-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is (= nil-result
           (value (do
                    (session/message {:op "inspect-refresh"})
                    (session/message {:op "inspect-refresh"})))))))

(deftest exception-integration-test
  (testing "eval op error handling"
    (let [exception-response (session/message {:op      "eval"
                                               :inspect "true"
                                               :code    "(first 1)"})]

      (testing "exprs that throw exceptions return an `ex` slot"
        (is (= "class java.lang.IllegalArgumentException"
               (:ex exception-response))))

      (testing "exprs that throw exceptions return an `err` slot"
        (is (-> exception-response ^String (:err) (.contains "IllegalArgumentException"))))))

  (testing "inspect-pop error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "pop exception")))]
      (let [response (session/message {:op "inspect-pop"})]
        (is (= (:status response) #{"inspect-pop-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: pop exception")))
        (is (:pp-stacktrace response)))))

  (testing "inspect-push error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "push exception")))]
      (let [response (session/message {:op "inspect-push" :idx 1})]
        (is (= (:status response) #{"inspect-push-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: push exception")))
        (is (:pp-stacktrace response)))))

  (testing "inspect-refresh error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "refresh exception")))]
      (let [response (session/message {:op "inspect-refresh"})]
        (is (= (:status response) #{"inspect-refresh-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: refresh exception")))
        (is (:pp-stacktrace response)))))

  (testing "inspect-next-page error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "next-page exception")))]
      (let [response (session/message {:op "inspect-next-page"})]
        (is (= (:status response) #{"inspect-next-page-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: next-page exception")))
        (is (:pp-stacktrace response)))))

  (testing "inspect-prev-page error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "prev-page exception")))]
      (let [response (session/message {:op "inspect-prev-page"})]
        (is (= (:status response) #{"inspect-prev-page-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: prev-page exception")))
        (is (:pp-stacktrace response)))))

  (testing "inspect-set-page-size error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "page-size exception")))]
      (let [response (session/message {:op "inspect-set-page-size" :page-size 10})]
        (is (= (:status response) #{"inspect-set-page-size-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: page-size exception")))
        (is (:pp-stacktrace response)))))

  (testing "inspect-set-max-atom-length error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "max-atom-length exception")))]
      (let [response (session/message {:op "inspect-set-max-atom-length" :max-atom-length 10})]
        (is (= (:status response) #{"inspect-set-max-atom-length-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: max-atom-length exception")))
        (is (:pp-stacktrace response)))))

  (testing "inspect-set-max-coll-size error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "max-coll-size exception")))]
      (let [response (session/message {:op "inspect-set-max-coll-size" :max-coll-size 10})]
        (is (= (:status response) #{"inspect-set-max-coll-size-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: max-coll-size exception")))
        (is (:pp-stacktrace response))))))

(deftest inspect-var-integration-test
  (testing "rendering a var"
    (is (= var-result
           (value (session/message {:op      "eval"
                                    :inspect "true"
                                    :code    "#'*assert*"}))))))

(deftest inspect-expr-integration-test
  (testing "rendering an expr"
    (is (= inspect-result
           (value (session/message {:op      "eval"
                                    :inspect "true"
                                    :code    code}))))))

(deftest push-integration-test
  (testing "pushing a rendered expr inspector idx"
    (is (= push-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    code})
                    (session/message {:op  "inspect-push"
                                      :idx 2})))))))

(deftest next-page-integration-test
  (testing "jumping to next page in a rendered expr inspector"
    (is (= next-page-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    "(map identity (range 35))"})
                    (session/message {:op "inspect-next-page"})))))))

(deftest prev-page-integration-test
  (testing "jumping to previous page in a rendered expr inspector"
    (is (= first-page-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    "(map identity (range 35))"})
                    (session/message {:op        "inspect-set-page-size"
                                      :page-size 5})
                    (session/message {:op "inspect-next-page"})
                    (session/message {:op "inspect-prev-page"})))))))

(deftest pop-integration-test
  (testing "popping a rendered expr inspector"
    (is (= inspect-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    code})
                    (session/message {:op  "inspect-push"
                                      :idx 1})
                    (session/message {:op "inspect-pop"})))))))

(deftest refresh-integration-test
  (testing "refreshing a rendered expr inspector"
    (is (= inspect-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    code})
                    (session/message {:op "inspect-refresh"})))))))

(deftest refresh-idempotent-integration-test
  (testing "refreshing a rendered expr inspector is idempotent"
    (is (= inspect-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    code})
                    (session/message {:op "inspect-refresh"})
                    (session/message {:op "inspect-refresh"})))))))

(deftest refresh-after-push-integration-test
  (testing "refreshing a rendered expr inspector after an idx is pushed"
    (is (= push-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    code})
                    (session/message {:op  "inspect-push"
                                      :idx 2})
                    (session/message {:op "inspect-refresh"})))))))

(deftest session-binding-integration-test
  (testing "session bindings can be inspected"
    (is (= inspect-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    code})
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    "*1"})))))))

(deftest page-size-integration-test
  (testing "page size can be changed in the eval op itself"
    (let [normal-page-size (session/message {:op      "eval"
                                             :inspect "true"
                                             :code    "(range 100)"})
          normal-page-2    (session/message {:op "inspect-next-page"})

          small-page-size  (session/message {:op        "eval"
                                             :inspect   "true"
                                             :code      "(range 100)"
                                             :page-size 5})
          small-page-2     (session/message {:op "inspect-next-page"})

          extract-text     #(-> % :value first)]
      (is (re-find #"Page size: 32, showing page: 1 of 4"
                   (extract-text normal-page-size)))
      (is (re-find #"Page size: 5, showing page: 1 of 20"
                   (extract-text small-page-size)))

      (is (re-find #"Page size: 32, showing page: 2 of 4"
                   (extract-text normal-page-2)))
      (is (re-find #"Page size: 5, showing page: 2 of 20"
                   (extract-text small-page-2)))))

  (testing "page size can be changed via the inspect-set-page-size op"
    (let [normal-page-size (session/message {:op      "eval"
                                             :inspect "true"
                                             :code    "(range 100)"})
          normal-page-2    (session/message {:op "inspect-next-page"})

          small-page-size  (session/message {:op        "inspect-set-page-size"
                                             :page-size 5})
          small-page-2     (session/message {:op "inspect-next-page"})

          extract-text     #(-> % :value first)]
      (is (re-find #"Page size: 32, showing page: 1 of 4"
                   (extract-text normal-page-size)))
      (is (re-find #"Page size: 5, showing page: 1 of 20"
                   (extract-text small-page-size)))

      (is (re-find #"Page size: 32, showing page: 2 of 4"
                   (extract-text normal-page-2)))
      (is (re-find #"Page size: 5, showing page: 2 of 20"
                   (extract-text small-page-2))))))

(deftest max-atom-length-integration-test
  ;; Default max length is 150, so 150 - 3 = 147 chars is too long because we
  ;; need to leave room for an opening quote and trailing ellipsis "xxxxxxxx...
  ;; NOTE: We'd prefer to use `orchard.inspect/*max-atom-length*` vs.
  ;; hard-coding `max-len` to 150 in case the default ever changes, but test
  ;; code isn't processed by mranderson and so can't refer to inlined deps
  ;; including orchard.
  ;; See also https://github.com/benedekfazekas/mranderson/issues/5
  (let [max-len 150 #_(var-get #'orchard.inspect/*max-atom-length*)
        _ (assert (> max-len 3) "inspect/*max-atom-length* is too short for this test.")
        too-long (pr-str [(string/join (repeat (- max-len 3) "x"))]) ;; 147
        trunc-len (- max-len 4) ;; 146
        x-pattern #(re-pattern (format "\"\\\\\"%s...\"" (string/join (repeat % "x"))))
        extract-text #(-> % :value first)]

    (testing "max atom length can be set for the eval op"
      (let [default-atom-length (session/message {:op      "eval"
                                                  :inspect "true"
                                                  :code    too-long})
            short-atom-length (session/message {:op              "eval"
                                                :inspect         "true"
                                                :code            too-long
                                                :max-atom-length 10})
            unchanged-default-atom-length (session/message {:op      "eval"
                                                            :inspect "true"
                                                            :code    too-long})]
        (is (re-find (x-pattern trunc-len)
                     (extract-text default-atom-length)))
        (is (re-find (x-pattern 6)
                     (extract-text short-atom-length)))
        (is (re-find (x-pattern trunc-len)
                     (extract-text unchanged-default-atom-length)))))

    (testing "max atom length can be changed without re-eval'ing last form"
      (let [default-atom-length (session/message {:op      "eval"
                                                  :inspect "true"
                                                  :code    too-long})
            shorten-atom-length (session/message {:op              "inspect-set-max-atom-length"
                                                  :max-atom-length 10})
            longer-atom-length (session/message {:op              "inspect-set-max-atom-length"
                                                 :max-atom-length 20})
            unchanged-default-atom-length (session/message {:op      "eval"
                                                            :inspect "true"
                                                            :code    too-long})]
        (is (re-find (x-pattern trunc-len)
                     (extract-text default-atom-length)))
        (is (re-find (x-pattern 6)
                     (extract-text shorten-atom-length)))
        (is (re-find (x-pattern 16)
                     (extract-text longer-atom-length)))
        (is (re-find (x-pattern trunc-len)
                     (extract-text unchanged-default-atom-length)))))))

(deftest max-coll-size-integration-test
  ;; See NOTE in `max-atom-length-integration-test` about hard-coding
  ;; `size-limit` here.
  (let [size-limit 5 #_(var-get #'orchard.inspect/*max-coll-size*)
        big-size (* 2 size-limit) ;; 10
        big-coll (format "[(range %d)]" big-size)
        coll-pattern (fn [len & [truncate]]
                       (re-pattern (format "\\( %s%s \\)"
                                           (string/join " " (range len))
                                           (if truncate " ..." ""))))
        extract-text #(-> % :value first)]

    (testing "max coll size can be set for the eval op"
      (let [default-coll-size (session/message {:op      "eval"
                                                :inspect "true"
                                                :code    big-coll})
            large-coll-size (session/message {:op            "eval"
                                              :inspect       "true"
                                              :code          big-coll
                                              :max-coll-size big-size})
            unchanged-default-coll-size (session/message {:op      "eval"
                                                          :inspect "true"
                                                          :code    big-coll})]
        (is (re-find (coll-pattern size-limit :truncate) ;; #"\( 0 1 2 3 4 ... \)"
                     (extract-text default-coll-size)))
        (is (re-find (coll-pattern big-size)             ;; #"\( 0 1 2 3 4 5 6 7 8 9 \)"
                     (extract-text large-coll-size)))
        (is (re-find (coll-pattern size-limit :truncate)
                     (extract-text unchanged-default-coll-size)))))

    (testing "max coll size can be changed without re-eval'ing last form"
      (let [default-coll-size (session/message {:op      "eval"
                                                :inspect "true"
                                                :code    big-coll})
            large-coll-size (session/message {:op            "inspect-set-max-coll-size"
                                              :max-coll-size big-size})
            smaller-coll-size (session/message {:op            "inspect-set-max-coll-size"
                                                :max-coll-size (dec big-size)})
            unchanged-default-coll-size (session/message {:op      "eval"
                                                          :inspect "true"
                                                          :code    big-coll})]
        (is (re-find (coll-pattern size-limit :truncate)
                     (extract-text default-coll-size)))
        (is (re-find (coll-pattern big-size)
                     (extract-text large-coll-size)))
        (is (re-find (coll-pattern (dec big-size) :truncate)
                     (extract-text smaller-coll-size)))
        (is (re-find (coll-pattern size-limit :truncate)
                     (extract-text unchanged-default-coll-size)))))))

(deftest print-length-independence-test
  (testing "*print-length* doesn't break rendering of long collections"
    (is (re-find #"showing page: \d+ of \d+"
                 (binding [*print-length* 10]
                   (first (:value (session/message {:op      "eval"
                                                    :inspect "true"
                                                    :code    "(range 100)"}))))))))

(deftest inspect-def-current-value-test
  (testing "inspect-def-current-value defines a var with the current inspector value"
    (is (= "{3 4}"
           (first (:value (do
                            (session/message {:op   "eval"
                                              :code "(def test-val [{1 2} {3 4}])"})
                            (session/message {:op      "eval"
                                              :inspect "true"
                                              :code    "test-val"})
                            (session/message {:op  "inspect-push"
                                              :idx 2})
                            (session/message {:op       "inspect-def-current-value"
                                              :ns       "user"
                                              :var-name "sub-map"})
                            (session/message {:op   "eval"
                                              :code "sub-map"}))))))))

(deftest inspect-tap-current-value-test
  (testing "inspect-tap-current-value taps the current inspector value"
    (session/message {:op   "eval"
                      :code "(def x (+ 3 4)))"})
    (session/message {:op "eval"
                      :inspect "true"
                      :code    "x"})
    (session/message {:op  "inspect-push"
                      :idx 1})
    (session/message {:op  "inspect-tap-current-value"})

    (let [max-time 10000
          ms 50
          iterations (long (/ max-time ms))]
      (loop [i 0]
        (when (and (not= "7" @inspect-tap-current-value-test-atom)
                   (< i iterations))
          (Thread/sleep ms)
          (recur (inc i)))))

    (is (= "7" @inspect-tap-current-value-test-atom))))
