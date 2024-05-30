(ns cider.nrepl.middleware.inspect-test
  (:require
   [matcher-combinators.matchers :as matchers]
   [cider.nrepl.middleware.inspect :as i]
   [cider.nrepl.test-session :as session]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [orchard.inspect]
   [orchard.info :as info]))

;; for `match?`
(require 'matcher-combinators.test)

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

(def nil-result
  '["nil" (:newline)])

(def any-var true)

(def var-result
  '("Class: "
    (:value "clojure.lang.Var" 0)
    (:newline)
    "Value: "
    (:value "true" 1)
    (:newline)
    (:newline)
    "--- Meta Information:"
    (:newline)
    "  "
    (:value ":line" 2) " = " (:value #"\d+" 3) (:newline)
    "  "
    (:value ":column" 4) " = " (:value #"\d+" 5) (:newline)
    "  "
    (:value ":file" 6) " = " (:value #"\".*cider/nrepl/middleware/inspect_test.clj\"" 7) (:newline)
    "  "
    (:value ":name" 8) " = " (:value "any-var" 9) (:newline)
    "  "
    (:value ":ns" 10) " = " (:value "cider.nrepl.middleware.inspect-test" 11) (:newline)
    (:newline)
    "--- Datafy:" (:newline)
    "  " "0" ". " (:value "true" 12) (:newline)))

(def code "(sorted-map :a {:b 1} :c \"a\" :d 'e :f [2 3])")

(def infinite-map-code "(let [m (java.util.HashMap.)] (.put m (symbol \"very long key to avoid stack overflow before limit reaches\") m) m)")

(def inspect-result
  '("Class: "
    (:value "clojure.lang.PersistentTreeMap" 0)
    (:newline)
    "Count: "
    "4"
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
  '("Class: "
    (:value "clojure.lang.PersistentArrayMap" 0)
    (:newline)
    "Count: "
    "1"
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

(def sibling-result
  '("Class: "
    (:value "java.lang.String" 0)
    (:newline)
    "Value: "
    "\"c\""
    (:newline)
    (:newline)
    "--- Print:"
    (:newline)
    "  "
    "c"
    (:newline)
    (:newline)
    "--- Path:"
    (:newline)
    "  "
    "(nth 2)"))

(def next-page-result
  '("Class: "
    (:value "clojure.lang.LazySeq" 0)
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
    "  " "Page size: 32, showing page: 2 of 2"
    (:newline)))

(def first-page-result
  '("Class: "
    (:value "clojure.lang.LazySeq" 0)
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
    "  " "Page size: 5, showing page: 1 of ?"
    (:newline)))

(defn value [{:keys [value]}]
  (edn/read-string (first value)))

(defn value-skip-header [resp]
  (drop 7 (value resp)))

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

  (testing "inspect-next-sibling error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "next-sibling exception")))]
      (let [response (session/message {:op "inspect-next-sibling"})]
        (is (= (:status response) #{"inspect-next-sibling-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: next-sibling exception")))
        (is (:pp-stacktrace response)))))

  (testing "inspect-previous-sibling error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "previous-sibling exception")))]
      (let [response (session/message {:op "inspect-previous-sibling"})]
        (is (= (:status response) #{"inspect-previous-sibling-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: previous-sibling exception")))
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
    (let [response (session/message {:op "inspect-set-page-size" :page-size 0})]
      (is (= #{"inspect-set-page-size-error" "done"} (:status response)))
      (is (= "class clojure.lang.ExceptionInfo" (:ex response)))
      (is (match? #".*Precondition failed: \(pos-int\? page-size\).*" (:err response)))
      (is (:pp-stacktrace response)))

    (let [response (session/message {:op "inspect-refresh" :page-size 0})]
      (is (= #{"inspect-refresh-error" "done"} (:status response)))
      (is (= "class clojure.lang.ExceptionInfo" (:ex response)))
      (is (match? #".*Precondition failed: \(pos-int\? page-size\).*" (:err response)))
      (is (:pp-stacktrace response))))

  (testing "inspect-set-max-atom-length error handling"
    (let [response (session/message {:op "inspect-set-max-atom-length" :max-atom-length 0})]
      (is (= #{"inspect-set-max-atom-length-error" "done"} (:status response)))
      (is (= "class clojure.lang.ExceptionInfo" (:ex response)))
      (is (match? #".*Precondition failed: \(pos-int\? max-atom-length\).*" (:err response)))
      (is (:pp-stacktrace response)))

    (let [response (session/message {:op "inspect-refresh" :max-atom-length 0})]
      (is (= #{"inspect-refresh-error" "done"} (:status response)))
      (is (= "class clojure.lang.ExceptionInfo" (:ex response)))
      (is (match? #".*Precondition failed: \(pos-int\? max-atom-length\).*" (:err response)))
      (is (:pp-stacktrace response))))

  (testing "inspect-set-max-coll-size error handling"
    (let [response (session/message {:op "inspect-set-max-coll-size" :max-coll-size 0})]
      (is (= #{"inspect-set-max-coll-size-error" "done"} (:status response)))
      (is (= "class clojure.lang.ExceptionInfo" (:ex response)))
      (is (match? #".*Precondition failed: \(pos-int\? max-coll-size\).*" (:err response)))
      (is (:pp-stacktrace response)))

    (let [response (session/message {:op "inspect-refresh" :max-coll-size 0})]
      (is (= #{"inspect-refresh-error" "done"} (:status response)))
      (is (= "class clojure.lang.ExceptionInfo" (:ex response)))
      (is (match? #".*Precondition failed: \(pos-int\? max-coll-size\).*" (:err response)))
      (is (:pp-stacktrace response)))))

(deftest inspect-var-integration-test
  (testing "rendering a var"
    (is (match? var-result
                (value (session/message {:op      "eval"
                                         :inspect "true"
                                         :code    "#'cider.nrepl.middleware.inspect-test/any-var"}))))))

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

(deftest next-sibling-integration-test
  (testing "jumping to next sibling in a rendered expr inspector"
    (is (= sibling-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    "(map identity (take 35 (cycle [\"a\" \"b\" \"c\"])))"})
                    (session/message {:op  "inspect-push"
                                      :idx 2})
                    (session/message {:op  "inspect-next-sibling"})))))))

(deftest previous-sibling-integration-test
  (testing "jumping to previous sibling in a rendered expr inspector"
    (is (= sibling-result
           (value (do
                    (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    "(map identity (take 35 (cycle [\"a\" \"b\" \"c\"])))"})
                    (session/message {:op  "inspect-push"
                                      :idx 4})
                    (session/message {:op  "inspect-previous-sibling"})))))))

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
                    (session/message {:op        "inspect-refresh"
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

(defn inspector-response [x]
  (-> x :value first read-string))

(deftest refresh-atom-test
  (testing "refreshing an inspected atom"
    (session/message {:op      "eval"
                      :inspect "true"
                      :code    "(def X (atom 111))"})
    (session/message {:op  "inspect-push"
                      :idx 1})
    (let [before "111"
          after  "112"]
      (is (match? (matchers/embeds [(list :value before 2)])
                  (-> {:op "inspect-refresh"}
                      session/message
                      inspector-response)))
      (testing "After modifying an atom"
        (session/message {:op      "eval"
                          :code    "(swap! X inc)"})
        (testing "Refreshing it shows its newest value"
          (is (match? (matchers/embeds [(list :value after 2)])
                      (-> {:op "inspect-refresh"}
                          session/message
                          inspector-response))))))))

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

  (testing "page size can be changed via the inspect-refresh op"
    (session/message {:op "inspect-clear"})
    (let [normal-page-size (session/message {:op      "eval"
                                             :inspect "true"
                                             :code    "(range 100)"})
          normal-page-2    (session/message {:op "inspect-next-page"})

          small-page-size  (session/message {:op        "inspect-refresh"
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
                   (extract-text small-page-2)))

      (testing "page size config is retained after inspecting new values"
        (is (re-find #"Page size: 5, showing page: 1 of 200"
                     (-> (session/message {:op      "eval"
                                           :inspect "true"
                                           :code    "(range 1000)"})
                         extract-text)))))))

(deftest max-atom-length-integration-test
  (let [max-len (:max-atom-length @#'orchard.inspect/default-inspector-config)
        xs #(string/join (repeat % "x"))
        fits (pr-str [(xs (- max-len 10))]) ;; 140
        too-long (pr-str [(xs (* max-len 2))]) ;; 300
        x-pattern #(str "\"" (xs %1) %2 "\\\"")
        extract-text #(-> % :value first)]

    (testing "max atom length can be set for the eval op"
      (is (string/includes? (-> (session/message {:op      "eval"
                                                  :inspect "true"
                                                  :code    fits})
                                extract-text)
                            (x-pattern (- max-len 10) "")))
      (is (string/includes? (-> (session/message {:op      "eval"
                                                  :inspect "true"
                                                  :code    too-long})
                                extract-text)
                            (x-pattern max-len "...")))
      (is (string/includes? (-> (session/message {:op              "eval"
                                                  :inspect         "true"
                                                  :code            too-long
                                                  :max-atom-length 10})
                                extract-text)
                            (x-pattern 10 "..."))))

    (testing "max atom length can be changed without re-eval'ing last form"
      (session/message {:op "inspect-clear"})
      (is (string/includes? (-> (session/message {:op      "eval"
                                                  :inspect "true"
                                                  :code    too-long})
                                extract-text)
                            (x-pattern max-len "...")))
      (is (string/includes? (-> (session/message {:op              "inspect-refresh"
                                                  :max-atom-length 10})
                                extract-text)
                            (x-pattern 10 "...")))
      (is (string/includes? (-> (session/message {:op              "inspect-refresh"
                                                  :max-atom-length 20})
                                extract-text)
                            (x-pattern 20 "..."))))))

(deftest max-value-length-integration-test
  (let [max-len (:max-value-length @#'orchard.inspect/default-inspector-config)
        extract-text #(-> % :value first)]

    (testing "max value length can be set for the eval op"
      (is (< max-len
             (-> (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    infinite-map-code})
                 extract-text
                 count)
             (+ max-len 300)))
      (is (< 500
             (-> (session/message {:op               "eval"
                                   :inspect          "true"
                                   :code             infinite-map-code
                                   :max-value-length 500})
                 extract-text
                 count)
             800)))))

(deftest max-coll-size-integration-test
  (let [size-limit (:max-coll-size @#'orchard.inspect/default-inspector-config)
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
            unchanged-default-coll-size (do (session/message {:op "inspect-clear"})
                                            (session/message {:op      "eval"
                                                              :inspect "true"
                                                              :code    big-coll}))]
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
            large-coll-size (session/message {:op            "inspect-refresh"
                                              :max-coll-size big-size})
            smaller-coll-size (session/message {:op            "inspect-refresh"
                                                :max-coll-size (dec big-size)})
            unchanged-default-coll-size (do (session/message {:op "inspect-clear"})
                                            (session/message {:op      "eval"
                                                              :inspect "true"
                                                              :code    big-coll}))]
        (is (re-find (coll-pattern size-limit :truncate)
                     (extract-text default-coll-size)))
        (is (re-find (coll-pattern big-size)
                     (extract-text large-coll-size)))
        (is (re-find (coll-pattern (dec big-size) :truncate)
                     (extract-text smaller-coll-size)))
        (is (re-find (coll-pattern size-limit :truncate)
                     (extract-text unchanged-default-coll-size)))))))

(deftest max-nested-depth-integration-test
  (let [nested-coll "'([[[[[[[[[[1]]]]]]]]]])"
        extract-text #(-> % :value first)]

    (testing "max nested depth can be set for the eval op"
      (let [default (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    nested-coll})
            limited (session/message {:op               "eval"
                                      :inspect          "true"
                                      :code             nested-coll
                                      :max-nested-depth 5})]
        (is (string/includes? (extract-text default)
                              "\"[ [ [ [ [ [ [ [ [ [ 1 ] ] ] ] ] ] ] ] ] ]\""))
        (is (string/includes? (extract-text limited)
                              "\"[ [ [ [ [ [ ... ] ] ] ] ] ]\""))))

    (testing "max nested depth can be changed without re-eval'ing last form"
      (session/message {:op "inspect-clear"})
      (let [default (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    nested-coll})
            limited (session/message {:op            "inspect-refresh"
                                      :max-nested-depth 5})]
        (is (string/includes? (extract-text default)
                              "\"[ [ [ [ [ [ [ [ [ [ 1 ] ] ] ] ] ] ] ] ] ]\""))
        (is (string/includes? (extract-text limited)
                              "\"[ [ [ [ [ [ ... ] ] ] ] ] ]\""))))))

(deftest no-spacious-integration-test
  (let [nested-coll "'([[[[[[[[[[1]]]]]]]]]])"
        extract-text #(-> % :value first)]

    (testing "spacious can be disabled for tighter rendering"
      (let [default (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    nested-coll})
            tight (session/message {:op       "eval"
                                    :inspect  "true"
                                    :code     nested-coll
                                    :spacious "false"})]
        (is (string/includes? (extract-text default)
                              "\"[ [ [ [ [ [ [ [ [ [ 1 ] ] ] ] ] ] ] ] ] ]\""))
        (is (string/includes? (extract-text tight)
                              "\"[[[[[[[[[[1]]]]]]]]]]\""))))))

(def normal-mode-prefix
  ["--- Contents:"
   [:newline]
   "  " "0" ". " [:value "1" number?]
   [:newline]
   "  " "1" ". " [:value "2" number?]
   [:newline]
   "  " "2" ". " [:value "3" number?]])

(def object-mode-prefix
  ["--- Instance fields:"
   [:newline]
   "  " [:value "_count" number?] " = " [:value "3" number?]
   [:newline]
   "  " [:value "_first" number?] " = " [:value "1" number?]
   [:newline]
   "  " [:value "_hash" number?] " = " [:value "0" number?]
   [:newline]
   "  " [:value "_hasheq" number?] " = " [:value "0" number?]
   [:newline]
   "  " [:value "_meta" number?] " = " [:value "nil" number?]
   [:newline]
   "  " [:value "_rest" number?] " = " [:value "( 2 3 )" number?]
   [:newline]])

(deftest object-view-mode-integration-test
  (testing "object view can be enabled to render the value as POJO"
    (is (match? (matchers/prefix object-mode-prefix)
                (value-skip-header (session/message {:op        "eval"
                                                     :inspect   "true"
                                                     :view-mode "object"
                                                     :code      "(list 1 2 3)"})))))

  (testing "object view can be enabled with refresh op"
    (session/message {:op "inspect-clear"})
    (session/message {:op      "eval"
                      :inspect "true"
                      :code    "(list 1 2 3)"})
    (is (match? (matchers/prefix object-mode-prefix)
                (value-skip-header (session/message {:op        "inspect-refresh"
                                                     :view-mode "object"}))))

    (testing "goes back to normal when :normal view-mode is passed"
      (is (match? (matchers/prefix normal-mode-prefix)
                  (value-skip-header (session/message {:op        "inspect-refresh"
                                                       :view-mode "normal"}))))))

  (testing "view-mode can be toggled with inspect-toggle-view-mode op"
    (session/message {:op "inspect-clear"})
    (is (match? (matchers/prefix normal-mode-prefix)
                (value-skip-header (session/message {:op      "eval"
                                                     :inspect "true"
                                                     :code    "(list 1 2 3)"}))))
    (is (match? (matchers/prefix object-mode-prefix)
                (value-skip-header (session/message {:op "inspect-toggle-view-mode"}))))
    (is (match? (matchers/prefix normal-mode-prefix)
                (value-skip-header (session/message {:op "inspect-toggle-view-mode"}))))))

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
        (when (and (not= 7 @inspect-tap-current-value-test-atom)
                   (< i iterations))
          (Thread/sleep ms)
          (recur (inc i)))))

    (is (= 7 @inspect-tap-current-value-test-atom))))

(deftest doc-fragments-test
  (when (contains? (info/info 'user `Thread/sleep)
                   :doc-fragments) ;; this test is only runnable with enrich-classpath active
    (testing "Responses for classes, methods and fields contain `:doc-fragments` attributes"
      (doseq [code ["java.lang.Thread"
                    "(-> java.lang.Thread .getMethods first)"
                    "(-> java.lang.Thread .getFields first)"]]
        (let [response (session/message {:op      "eval"
                                         :inspect "true"
                                         :code    code})]
          (is (contains? response :doc-fragments))
          (is (contains? response :doc-first-sentence-fragments))
          (is (contains? response :doc-block-tags-fragments))))))

  (testing "Responses for other objects do not contain `:doc-fragments` attributes"
    (doseq [code ["1"
                  "{}"
                  "true"]]
      (let [response (session/message {:op      "eval"
                                       :inspect "true"
                                       :code    code})]
        (is (not (contains? response :doc-fragments)))
        (is (not (contains? response :doc-first-sentence-fragments)))
        (is (not (contains? response :doc-block-tags-fragments)))))))
