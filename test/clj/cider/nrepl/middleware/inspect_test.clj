(ns cider.nrepl.middleware.inspect-test
  (:require [cider.nrepl.middleware.inspect :as i]
            [cider.nrepl.middleware.util.inspect :as inspect]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(def nil-result ["(\"nil\" (:newline))"])

(def var-result ["(\"Class\" \": \" (:value \"clojure.lang.Var\" 0) (:newline) \"Meta Information: \" (:newline) \"  \" (:value \":ns\" 1) \" = \" (:value \"clojure.core\" 2) (:newline) \"  \" (:value \":name\" 3) \" = \" (:value \"*assert*\" 4) (:newline) \"Value: \" (:value \"true\" 5))"])

(def code "(sorted-map :a {:b 1} :c \"a\" :d 'e :f [2 3])")

(def eval-result (eval (read-string code)))

(def inspect-result ["(\"Class\" \": \" (:value \"clojure.lang.PersistentTreeMap\" 0) (:newline) \"Contents: \" (:newline) \"  \" \"0\" \". \" (:value \"[ :a { :b 1 } ]\" 1) (:newline) \"  \" \"1\" \". \" (:value \"[ :c \\\"a\\\" ]\" 2) (:newline) \"  \" \"2\" \". \" (:value \"[ :d e ]\" 3) (:newline) \"  \" \"3\" \". \" (:value \"[ :f [ 2 3 ] ]\" 4) (:newline))"])

(def push-result ["(\"Class\" \": \" (:value \"clojure.lang.PersistentTreeMap$BlackVal\" 0) (:newline) \"Contents: \" (:newline) \"  \" \"0\" \". \" (:value \":a\" 1) (:newline) \"  \" \"1\" \". \" (:value \"{ :b 1 }\" 2) (:newline) (:newline) \"  Path: (find :a)\")"])

(def long-sequence (range 70))
(def long-vector (vec (range 70)))
(def long-map (zipmap (range 70) (range 70)))

(def next-page-result ["(\"Class\" \": \" (:value \"clojure.lang.LazySeq\" 0) (:newline) \"Contents: \" (:newline) \"  ...\" (:newline) \"  \" \"32\" \". \" (:value \"32\" 1) (:newline) \"  \" \"33\" \". \" (:value \"33\" 2) (:newline) \"  \" \"34\" \". \" (:value \"34\" 3) (:newline) (:newline) \"  Page size: 32, showing page: 2 of 2\")"])
(def first-page-result ["(\"Class\" \": \" (:value \"clojure.lang.LazySeq\" 0) (:newline) \"Contents: \" (:newline) \"  \" \"0\" \". \" (:value \"0\" 1) (:newline) \"  \" \"1\" \". \" (:value \"1\" 2) (:newline) \"  \" \"2\" \". \" (:value \"2\" 3) (:newline) \"  \" \"3\" \". \" (:value \"3\" 4) (:newline) \"  \" \"4\" \". \" (:value \"4\" 5) (:newline) \"  ...\" (:newline) \"  Page size: 5, showing page: 1 of ?\")"])

(defn inspect
  [value]
  (inspect/start (inspect/fresh) value))

(defn render
  [inspector]
  (vector (pr-str (:rendered inspector))))

(deftest nil-test
  (testing "nil renders correctly"
    (is (= nil-result
           (-> nil
               inspect
               render)))))

(deftest pop-empty-test
  (testing "popping an empty inspector renders nil"
    (is (= nil-result
           (-> (inspect/fresh)
               inspect/up
               render)))))

(deftest pop-empty-idempotent-test
  (testing "popping an empty inspector is idempotent"
    (is (= nil-result
           (-> (inspect/fresh)
               inspect/up
               inspect/up
               render)))))

(deftest push-empty-test
  (testing "pushing an empty inspector index renders nil"
    (is (= nil-result
           (-> (inspect/fresh)
               (inspect/down 1)
               render)))))

(deftest push-empty-idempotent-test
  (testing "pushing an empty inspector index is idempotent"
    (is (= nil-result
           (-> (inspect/fresh)
               (inspect/down 1)
               (inspect/down 1)
               render)))))

(deftest inspect-var-test
  (testing "rendering a var"
    (is (= var-result
           (-> #'*assert*
               inspect
               render)))))

(deftest inspect-expr-test
  (testing "rendering an expr"
    (is (= inspect-result
           (-> eval-result
               inspect
               render)))))

(deftest push-test
  (testing "pushing a rendered expr inspector idx"
    (is (= push-result
           (-> eval-result
               inspect
               (inspect/down 1)
               render)))))

(deftest pop-test
  (testing "popping a rendered expr inspector"
    (is (= inspect-result
           (-> eval-result
               inspect
               (inspect/down 1)
               inspect/up
               render)))))

(deftest pagination-test
  (testing "big collections are paginated"
    (is (= 33 (-> long-sequence
                  inspect
                  :counter)))
    (is (= 33 (-> long-map
                  inspect
                  :counter)))
    (is (.startsWith (-> long-vector
                         inspect
                         :rendered
                         last)
                     "  Page size:")))
  (testing "small collections are not paginated"
    (is (= '(:newline)
           (-> (range 10)
               inspect
               :rendered
               last))))
  (testing "changing page size"
    (is (= 21 (-> long-sequence
                  inspect
                  (inspect/set-page-size 20)
                  :counter)))
    (is (= '(:newline) (-> long-sequence
                           inspect
                           (inspect/set-page-size 200)
                           :rendered
                           last))))
  (testing "uncounted collections have their size determined on the last page"
    (is (= "  Page size: 32, showing page: 2 of 2"
           (-> (range 50)
               inspect
               inspect/next-page
               :rendered
               last))))
  (testing "next-page and prev-page are bound to collection size"
    (is (= 2
           (-> long-vector
               inspect
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               inspect/next-page
               :current-page)))
    (is (= 0
           (-> long-vector
               inspect
               inspect/prev-page
               inspect/prev-page
               :current-page)))
    (is (= 1
           (-> long-vector
               inspect
               inspect/next-page
               inspect/next-page
               inspect/prev-page
               inspect/next-page
               inspect/prev-page
               :current-page)))))

(deftest path-test
  (testing "inspector tracks the path in the data structure"
    (is (.endsWith (first (-> long-map
                              inspect
                              (inspect/down 20)
                              render))
                   "\"  Path: (find 50)\")"))
    (is (.endsWith (first (-> long-map
                              inspect
                              (inspect/down 20)
                              (inspect/down 1)
                              render))
                   "\"  Path: (find 50) first\")"))
    (is (.endsWith (first (-> long-map
                              inspect
                              (inspect/down 20)
                              (inspect/down 2)
                              render))
                   "\"  Path: (get 50)\")"))
    (is (.endsWith (first (-> long-map
                              inspect
                              (inspect/down 20)
                              (inspect/down 2)
                              (inspect/down 0)
                              render))
                   "\"  Path: (get 50) class\")")))
  (testing "doesn't show path if unknown navigation has happened"
    (is (.endsWith (first (-> long-map
                              inspect
                              (inspect/down 20)
                              (inspect/down 2)
                              (inspect/down 0)
                              (inspect/down 1)
                              render))
                   "(:newline))")))
  (testing "doesn't show the path in the top level"
    (is (.endsWith (first (-> [1 2 3]
                              inspect
                              render))
                   "(:newline))"))))

;; integration tests

(use-fixtures :each session/session-fixture)

(deftest nil-integration-test
  (testing "nil renders correctly"
    (is (= nil-result
           (:value (session/message {:op "eval"
                                     :inspect "true"
                                     :code "nil"}))))))

(deftest pop-empty-integration-test
  (testing "popping an empty inspector renders nil"
    (is (= nil-result
           (:value (session/message {:op "inspect-pop"}))))))

(deftest pop-empty-idempotent-integration-test
  (testing "popping an empty inspector is idempotent"
    (is (= nil-result
           (:value (do
                     (session/message {:op "inspect-pop"})
                     (session/message {:op "inspect-pop"})))))))

(deftest push-empty-integration-test
  (testing "pushing an empty inspector index renders nil"
    (is (= nil-result
           (:value (session/message {:op "inspect-push"
                                     :idx 1}))))))

(deftest push-empty-idempotent-integration-test
  (testing "pushing an empty inspector index is idempotent"
    (is (= nil-result
           (:value (do
                     (session/message {:op "inspect-push"
                                       :idx 1})
                     (session/message {:op "inspect-push"
                                       :idx 1})))))))

(deftest refresh-empty-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is (= nil-result
           (:value (session/message {:op "inspect-refresh"}))))))

(deftest refresh-empty-idempotent-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is (= nil-result
           (:value (do
                     (session/message {:op "inspect-refresh"})
                     (session/message {:op "inspect-refresh"})))))))

(deftest exception-integration-test
  (testing "eval op error handling"
    (let [exception-response (session/message {:op "eval"
                                               :inspect "true"
                                               :code "(first 1)"})]

      (testing "exprs that throw exceptions return an `ex` slot"
        (is (= "class java.lang.IllegalArgumentException"
               (:ex exception-response))))

      ;;TODO: The :err slot is missing when running this through the Cider test-runner
      (testing "exprs that throw exceptions return an `err` slot"
        (is (.startsWith (:err exception-response)
                         "IllegalArgumentException")))))

  (testing "inspect-pop error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "pop exception")))]
      (let [response (session/message {:op "inspect-pop"})]
        (is (= (:status response) #{"inspect-pop-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (.startsWith (:err response) "java.lang.Exception: pop exception"))
        (is (:pp-stacktrace response)))))

  (testing "inspect-push error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "push exception")))]
      (let [response (session/message {:op "inspect-push" :idx 1})]
        (is (= (:status response) #{"inspect-push-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (.startsWith (:err response) "java.lang.Exception: push exception"))
        (is (:pp-stacktrace response)))))

  (testing "inspect-refresh error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "refresh exception")))]
      (let [response (session/message {:op "inspect-refresh"})]
        (is (= (:status response) #{"inspect-refresh-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (.startsWith (:err response) "java.lang.Exception: refresh exception"))
        (is (:pp-stacktrace response)))))

  (testing "inspect-next-page error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "next-page exception")))]
      (let [response (session/message {:op "inspect-next-page"})]
        (is (= (:status response) #{"inspect-next-page-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (.startsWith (:err response) "java.lang.Exception: next-page exception"))
        (is (:pp-stacktrace response)))))

  (testing "inspect-prev-page error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "prev-page exception")))]
      (let [response (session/message {:op "inspect-prev-page"})]
        (is (= (:status response) #{"inspect-prev-page-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (.startsWith (:err response) "java.lang.Exception: prev-page exception"))
        (is (:pp-stacktrace response)))))

  (testing "inspect-set-page-size error handling"
    (with-redefs [i/swap-inspector! (fn [& _] (throw (Exception. "page-size exception")))]
      (let [response (session/message {:op "inspect-set-page-size" :page-size 10})]
        (is (= (:status response) #{"inspect-set-page-size-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (.startsWith (:err response) "java.lang.Exception: page-size exception"))
        (is (:pp-stacktrace response))))))

(deftest inspect-var-integration-test
  (testing "rendering a var"
    (is (= var-result
           (:value (session/message {:op "eval"
                                     :inspect "true"
                                     :code "#'*assert*"}))))))

(deftest inspect-expr-integration-test
  (testing "rendering an expr"
    (is (= inspect-result
           (:value (session/message {:op "eval"
                                     :inspect "true"
                                     :code code}))))))

(deftest push-integration-test
  (testing "pushing a rendered expr inspector idx"
    (is (= push-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-push"
                                       :idx 1})))))))

(deftest next-page-integration-test
  (testing "jumping to next page in a rendered expr inspector"
    (is (= next-page-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code "(map identity (range 35))"})
                     (session/message {:op "inspect-next-page"})))))))

(deftest prev-page-integration-test
  (testing "jumping to previous page in a rendered expr inspector"
    (is (= first-page-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code "(map identity (range 35))"})
                     (session/message {:op "inspect-set-page-size"
                                       :page-size 5})
                     (session/message {:op "inspect-next-page"})
                     (session/message {:op "inspect-prev-page"})))))))

(deftest pop-integration-test
  (testing "popping a rendered expr inspector"
    (is (= inspect-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-push"
                                       :idx 1})
                     (session/message {:op "inspect-pop"})))))))

(deftest refresh-integration-test
  (testing "refreshing a rendered expr inspector"
    (is (= inspect-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-refresh"})))))))

(deftest refresh-idempotent-integration-test
  (testing "refreshing a rendered expr inspector is idempotent"
    (is (= inspect-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-refresh"})
                     (session/message {:op "inspect-refresh"})))))))

(deftest refresh-after-push-integration-test
  (testing "refreshing a rendered expr inspector after an idx is pushed"
    (is (= push-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "inspect-push"
                                       :idx 1})
                     (session/message {:op "inspect-refresh"})))))))

(deftest session-binding-integration-test
  (testing "session bindings can be inspected"
    (is (= inspect-result
           (:value (do
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code code})
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code "*1"})))))))

(deftest page-size-integration-test
  (testing "page size can be changed in the eval op itself"
    (let [normal-page-size (session/message {:op "eval"
                                             :inspect "true"
                                             :code "(range 100)"})
          normal-page-2    (session/message {:op "inspect-next-page"})

          small-page-size  (session/message {:op "eval"
                                             :inspect "true"
                                             :code "(range 100)"
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
    (let [normal-page-size (session/message {:op "eval"
                                             :inspect "true"
                                             :code "(range 100)"})
          normal-page-2    (session/message {:op "inspect-next-page"})

          small-page-size  (session/message {:op "inspect-set-page-size"
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
