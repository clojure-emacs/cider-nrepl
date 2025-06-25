(ns cider.nrepl.middleware.cljs-inspect-test
  (:require
   [cider.nrepl.middleware.inspect :as i]
   [cider.nrepl.middleware.inspect-test :as inspect-test]
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.edn :as edn]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as matchers]))

(def nil-result
  ["Value: nil" [:newline] [:newline] "--- Contents:" [:newline] string?])

(def code "(sorted-map :a {:b 1} :c \"a\" :d 'e :f [2 3])")

(def inspect-contents
  ["--- Contents:" [:newline]
   "  " [:value ":a" 1] " = " [:value "{:b 1}" 2] [:newline]
   "  " [:value ":c" 3] " = " [:value "\"a\"" 4] [:newline]
   "  " [:value ":d" 5] " = " [:value "e" 6] [:newline]
   "  " [:value ":f" 7] " = " [:value "[2 3]" 8]])

(def push-contents
  ["--- Contents:" [:newline]
   "  " [:value ":b" 1] " = " [:value "1" 2]])

(def next-page-contents
  ["--- Contents:" [:newline]
   "  ..." [:newline]
   "  32. " [:value "32" 1] [:newline]
   "  33. " [:value "33" 2] [:newline]
   "  34. " [:value "34" 3]])

(defn value [{:keys [value]}]
  (->> (edn/read-string (first value))
       ;; Merge strings
       (reduce (fn [acc x]
                 (let [lst (peek acc)]
                   (if (and (string? x) (string? lst))
                     (conj (pop acc) (str lst x))
                     (conj acc x))))
               [])))

;; integration tests

(defn with-fresh-inspector
  [f]
  (dorun (session/message {:op "inspect-clear"}))
  (f))

(use-fixtures :once piggieback-fixture)
(use-fixtures :each with-fresh-inspector)

(deftest nil-integration-test
  (testing "nil renders correctly"
    (is+ (matchers/prefix nil-result)
         (value (session/message {:op "eval"
                                  :inspect "true"
                                  :code "nil"})))))

(deftest pop-empty-integration-test
  (testing "popping an empty inspector renders nil"
    (is+ (matchers/prefix nil-result)
         (value (session/message {:op "inspect-pop"})))))

(deftest pop-empty-idempotent-integration-test
  (testing "popping an empty inspector is idempotent"
    (is+ (matchers/prefix nil-result)
         (value (do
                  (session/message {:op "inspect-pop"})
                  (session/message {:op "inspect-pop"}))))))

(deftest push-empty-integration-test
  (testing "pushing an empty inspector index renders nil"
    (is+ (matchers/prefix nil-result)
         (value (session/message {:op "inspect-push"
                                  :idx 2})))))

(deftest push-empty-idempotent-integration-test
  (testing "pushing an empty inspector index is idempotent"
    (is+ (matchers/prefix nil-result)
         (value (do
                  (session/message {:op "inspect-push"
                                    :idx 2})
                  (session/message {:op "inspect-push"
                                    :idx 2}))))))

(deftest refresh-empty-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is+ (matchers/prefix nil-result)
         (value (session/message {:op "inspect-refresh"})))))

(deftest refresh-empty-idempotent-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is+ (matchers/prefix nil-result)
         (value (do
                  (session/message {:op "inspect-refresh"})
                  (session/message {:op "inspect-refresh"}))))))

(deftest inspect-expr-integration-test
  (testing "rendering an expr"
    (is+ inspect-contents
         (-> (session/message {:op "eval"
                               :inspect "true"
                               :code code})
             value (inspect-test/section "Contents")))))

(deftest push-integration-test
  (testing "pushing a rendered expr inspector idx"
    (is+ push-contents
         (-> (do
               (session/message {:op "eval"
                                 :inspect "true"
                                 :code code})
               (session/message {:op "inspect-push"
                                 :idx 2}))
             value (inspect-test/section "Contents")))))

(deftest pop-integration-test
  (testing "popping a rendered expr inspector"
    (is+ inspect-contents
         (-> (do
               (session/message {:op "eval"
                                 :inspect "true"
                                 :code code})
               (session/message {:op "inspect-push"
                                 :idx 2})
               (session/message {:op "inspect-pop"}))
             value (inspect-test/section "Contents")))))

(deftest next-page-integration-test
  (testing "jumping to next page in a rendered expr inspector"
    (is+ next-page-contents
         (-> (do (session/message {:op "eval"
                                   :inspect "true"
                                   :code "(list* (map identity (range 35)))"})
                 (session/message {:op "inspect-next-page"}))
             value (inspect-test/section "Contents")))))

(deftest refresh-integration-test
  (testing "refreshing a rendered expr inspector"
    (is inspect-contents
        (-> (do
              (session/message {:op "eval"
                                :inspect "true"
                                :code code})
              (session/message {:op "inspect-refresh"}))
            value (inspect-test/section "Contents")))))

(deftest session-binding-integration-test
  (testing "session bindings can be inspected"
    (is+ inspect-contents
         (-> (do
               (session/message {:op "eval"
                                 :inspect "true"
                                 :code code})
               (session/message {:op "eval"
                                 :inspect "true"
                                 :code "*1"}))
             value (inspect-test/section "Contents")))))
