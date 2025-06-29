(ns cider.nrepl.middleware.inspect-test
  (:require
   [matcher-combinators.matchers :as matchers]
   [cider.nrepl.middleware.inspect :as i]
   [cider.nrepl.test-session :as session]
   [cider.nrepl.middleware.info-test :as info-test]
   [cider.test-helpers :refer :all]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [orchard.java]
   [orchard.inspect]
   [orchard.info :as info]))

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

(defn- section? [name rendered]
  (when (string? rendered)
    (re-find (re-pattern (str "^--- " name)) rendered)))

(defn section [rendered name]
  (->> rendered
       (drop-while #(not (section? name %)))
       (take-while #(or (section? name %)
                        (not (section? ".*" %))))
       ;; Trim newlines
       reverse
       (drop-while #(= % [:newline]))
       reverse
       (not-empty)))

(def nil-result
  ["Value: nil" [:newline] [:newline] "--- Contents:" [:newline] string? [:newline]])

(def any-var true)

(def code "(sorted-map :a {:b 1} :c \"a\" :d 'e :f [2 3])")

(def infinite-map-code "(let [m (java.util.HashMap.)] (.put m (symbol \"very long key to avoid stack overflow before limit reaches\") m) m)")

(def inspect-contents
  ["--- Contents:" [:newline]
   "  " [:value ":a" 1] " = " [:value "{:b 1}" 2] [:newline]
   "  " [:value ":c" 3] " = " [:value "\"a\"" 4] [:newline]
   "  " [:value ":d" 5] " = " [:value "e" 6] [:newline]
   "  " [:value ":f" 7] " = " [:value "[2 3]" 8]])

(def push-contents
  ["--- Contents:" [:newline]
   "  " [:value ":b" 1] " = " [:value "1" 2]])

(def sibling-result
  ["Class: " [:value "java.lang.String" 0] [:newline]
   "Value: \"c\"" [:newline]
   [:newline]
   "--- Print:" [:newline]
   "  c" [:newline]
   [:newline]
   "--- Path:" [:newline]
   "  (nth 2)" [:newline] [:newline]
   #"--- View mode" [:newline]
   "  ●normal object pretty"])

(defn value [{:keys [value]}]
  (->> (edn/read-string (first value))
       ;; Merge strings
       (reduce (fn [acc x]
                 (let [lst (peek acc)]
                   (if (and (string? x) (string? lst))
                     (conj (pop acc) (str lst x))
                     (conj acc x))))
               [])))

(defn value-skip-header [resp]
  (drop-while #(not (and (string? %) (.startsWith ^String % "---")))
              (value resp)))

;; integration tests

(deftest nil-integration-test
  (testing "nil renders correctly"
    (is+ nil-result
         (value (session/message {:op      "eval"
                                  :inspect "true"
                                  :code    "nil"})))))

(deftest pop-empty-integration-test
  (testing "popping an empty inspector renders nil"
    (is+ nil-result
         (value (session/message {:op "inspect-pop"})))))

(deftest pop-empty-idempotent-integration-test
  (testing "popping an empty inspector is idempotent"
    (is+ nil-result
         (value (do
                  (session/message {:op "inspect-pop"})
                  (session/message {:op "inspect-pop"}))))))

(deftest push-empty-integration-test
  (testing "pushing an empty inspector index renders nil"
    (is+ nil-result
         (value (session/message {:op  "inspect-push"
                                  :idx 1})))))

(deftest push-empty-idempotent-integration-test
  (testing "pushing an empty inspector index is idempotent"
    (is+ nil-result
         (value (do
                  (session/message {:op  "inspect-push"
                                    :idx 1})
                  (session/message {:op  "inspect-push"
                                    :idx 1}))))))

(deftest refresh-empty-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is+ nil-result
         (value (session/message {:op "inspect-refresh"})))))

(deftest refresh-empty-idempotent-integration-test
  (testing "refreshing an empty inspector renders nil"
    (is+ nil-result
         (value (do
                  (session/message {:op "inspect-refresh"})
                  (session/message {:op "inspect-refresh"}))))))

(deftest inspect-expr-integration-test
  (testing "rendering an expr"
    (is+ inspect-contents
         (-> (session/message {:op      "eval"
                               :inspect "true"
                               :code    code})
             value (section "Contents")))))

(deftest push-integration-test
  (testing "pushing a rendered expr inspector idx"
    (is+ push-contents
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    code})
                 (session/message {:op  "inspect-push"
                                   :idx 2}))
             value (section "Contents")))))

(deftest next-sibling-integration-test
  (testing "jumping to next sibling in a rendered expr inspector"
    (is+ ["--- Print:" [:newline] "  c"]
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    "(map identity (take 35 (cycle [\"a\" \"b\" \"c\"])))"})
                 (session/message {:op  "inspect-push"
                                   :idx 2})
                 (session/message {:op  "inspect-next-sibling"}))
             value (section "Print")))))

(deftest previous-sibling-integration-test
  (testing "jumping to previous sibling in a rendered expr inspector"
    (is+ ["--- Print:" [:newline] "  c"]
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    "(map identity (take 35 (cycle [\"a\" \"b\" \"c\"])))"})
                 (session/message {:op  "inspect-push"
                                   :idx 4})
                 (session/message {:op  "inspect-previous-sibling"}))
             value (section "Print")))))

(deftest next-page-integration-test
  (testing "jumping to next page in a rendered expr inspector"
    (is+ ["--- Contents:" [:newline]
          "  ..." [:newline]
          "  32. " [:value "32" 1] [:newline]
          "  33. " [:value "33" 2] [:newline]
          "  34. " [:value "34" 3]]
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    "(map identity (range 35))"})
                 (session/message {:op "inspect-next-page"}))
             value (section "Contents")))))

(deftest prev-page-integration-test
  (testing "jumping to previous page in a rendered expr inspector"
    (is+ ["--- Contents:" [:newline]
          "  0. " [:value "0" 1] [:newline]
          "  1. " [:value "1" 2] [:newline]
          "  2. " [:value "2" 3] [:newline]
          "  3. " [:value "3" 4] [:newline]
          "  4. " [:value "4" 5] [:newline]
          "  ..."]
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    "(map identity (range 35))"})
                 (session/message {:op        "inspect-refresh"
                                   :page-size 5})
                 (session/message {:op "inspect-next-page"})
                 (session/message {:op "inspect-prev-page"}))
             value (section "Contents")))))

(deftest pop-integration-test
  (testing "popping a rendered expr inspector"
    (is+ inspect-contents
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    code})
                 (session/message {:op  "inspect-push"
                                   :idx 1})
                 (session/message {:op "inspect-pop"}))
             value (section "Contents")))))

(deftest refresh-integration-test
  (testing "refreshing a rendered expr inspector"
    (is+ inspect-contents
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    code})
                 (session/message {:op "inspect-refresh"}))
             value (section "Contents")))))

(deftest refresh-idempotent-integration-test
  (testing "refreshing a rendered expr inspector is idempotent"
    (is+ inspect-contents
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    code})
                 (session/message {:op "inspect-refresh"})
                 (session/message {:op "inspect-refresh"}))
             value (section "Contents")))))

(deftest refresh-after-push-integration-test
  (testing "refreshing a rendered expr inspector after an idx is pushed"
    (is+ push-contents
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    code})
                 (session/message {:op  "inspect-push"
                                   :idx 2})
                 (session/message {:op "inspect-refresh"}))
             value (section "Contents")))))

(defn inspector-response [x]
  (-> x :value first read-string))

(deftest session-binding-integration-test
  (testing "session bindings can be inspected"
    (is+ inspect-contents
         (-> (do (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    code})
                 (session/message {:op      "eval"
                                   :inspect "true"
                                   :code    "*1"}))
             value (section "Contents")))))

(deftest page-size-integration-test
  (testing "page size can be changed via the inspect-refresh op"
    (is+ {:value [#"Page size: 32, showing page: 1 of 4"]}
         (session/message {:op      "eval"
                           :inspect "true"
                           :code    "(range 100)"}))
    (is+ {:value [#"Page size: 32, showing page: 2 of 4"]}
         (session/message {:op "inspect-next-page"}))
    (is+ {:value [#"Page size: 5, showing page: 1 of 20"]}
         (session/message {:op "inspect-refresh", :page-size 5}))
    (is+ {:value [#"Page size: 5, showing page: 2 of 20"]}
         (session/message {:op "inspect-next-page"})))

  (testing "page size config is retained after inspecting new values"
    (is+ {:value [#"Page size: 5, showing page: 1 of 200"]}
         (session/message {:op      "eval"
                           :inspect "true"
                           :code    "(range 1000)"}))))

(deftest max-atom-length-integration-test
  (let [max-len (:max-atom-length @#'orchard.inspect/default-inspector-config)
        xs #(str/join (repeat % "x"))
        fits (pr-str [(xs (- max-len 10))]) ;; 140
        too-long (pr-str [(xs (* max-len 2))]) ;; 300
        x-pattern #(str "\"" (xs %1) %2 "\\\"")
        extract-text #(-> % :value first)]

    (testing "max atom length can be set for the eval op"
      (is (str/includes? (-> (session/message {:op      "eval"
                                               :inspect "true"
                                               :code    fits})
                             extract-text)
                         (x-pattern (- max-len 10) "")))
      (is (str/includes? (-> (session/message {:op      "eval"
                                               :inspect "true"
                                               :code    too-long})
                             extract-text)
                         (x-pattern max-len "...")))
      (is (str/includes? (-> (session/message {:op              "eval"
                                               :inspect         "true"
                                               :code            too-long
                                               :max-atom-length 10})
                             extract-text)
                         (x-pattern 10 "..."))))

    (testing "max atom length can be changed without re-eval'ing last form"
      (session/message {:op "inspect-clear"})
      (is (str/includes? (-> (session/message {:op      "eval"
                                               :inspect "true"
                                               :code    too-long})
                             extract-text)
                         (x-pattern max-len "...")))
      (is (str/includes? (-> (session/message {:op              "inspect-refresh"
                                               :max-atom-length 10})
                             extract-text)
                         (x-pattern 10 "...")))
      (is (str/includes? (-> (session/message {:op              "inspect-refresh"
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
             (+ max-len 400)))
      (is (< 500
             (-> (session/message {:op               "eval"
                                   :inspect          "true"
                                   :code             infinite-map-code
                                   :max-value-length 500})
                 extract-text
                 count)
             900)))))

(deftest max-coll-size-integration-test
  (let [size-limit (:max-coll-size @#'orchard.inspect/default-inspector-config)
        big-size (* 2 size-limit) ;; 10
        big-coll (format "[(range %d)]" big-size)
        coll-pattern (fn [len & [truncate]]
                       (re-pattern (format "\\(%s%s\\)"
                                           (str/join " " (range len))
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
        (is (re-find (coll-pattern size-limit :truncate) ;; #"\(0 1 2 3 4 ...\)"
                     (extract-text default-coll-size)))
        (is (re-find (coll-pattern big-size)             ;; #"\(0 1 2 3 4 5 6 7 8 9\)"
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
        (is (str/includes? (extract-text default)
                           "\"[[[[[[[[[[1]]]]]]]]]]\""))
        (is (str/includes? (extract-text limited)
                           "\"[[[[[[...]]]]]]\""))))

    (testing "max nested depth can be changed without re-eval'ing last form"
      (session/message {:op "inspect-clear"})
      (let [default (session/message {:op      "eval"
                                      :inspect "true"
                                      :code    nested-coll})
            limited (session/message {:op            "inspect-refresh"
                                      :max-nested-depth 5})]
        (is (str/includes? (extract-text default)
                           "\"[[[[[[[[[[1]]]]]]]]]]\""))
        (is (str/includes? (extract-text limited)
                           "\"[[[[[[...]]]]]]\""))))))

(def normal-mode-prefix
  ["--- Contents:" [:newline]
   "  0. " [:value "1" number?] [:newline]
   "  1. " [:value "2" number?] [:newline]
   "  2. " [:value "3" number?]])

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
   "  " [:value "_rest" number?] " = " [:value "(2 3)" number?]
   [:newline]])

(def table-mode-prefix
  ["--- Contents:" [:newline]
   [:newline]
   "  |  " [:value "#" pos?] " | " [:value ":a" pos?] " | " [:newline]
   "  |----+----|" [:newline]
   "  |  " [:value "0" pos?] " |  " [:value "1" pos?] " | " [:newline]
   "  |  " [:value "1" pos?] " |  " [:value "1" pos?] " | " [:newline]
   "  |  " [:value "2" pos?] " |  " [:value "1" pos?] " | " [:newline]])

(deftest object-view-mode-integration-test
  (testing "view-mode can be toggled with inspect-toggle-view-mode op"
    (session/message {:op "inspect-clear"})
    (is+ (matchers/prefix normal-mode-prefix)
         (value-skip-header (session/message {:op      "eval"
                                              :inspect "true"
                                              :code    "(list 1 2 3)"})))
    (is+ (matchers/prefix object-mode-prefix)
         (value-skip-header (session/message {:op "inspect-toggle-view-mode"})))
    (is+ (matchers/prefix normal-mode-prefix)
         (value-skip-header (session/message {:op "inspect-toggle-view-mode"}))))

  (testing "view-mode is automatically reset after navigating down"
    (session/message {:op "inspect-clear"})
    (session/message {:op      "eval"
                      :inspect "true"
                      :code    "(list 1 2 3)"})
    (is+ (matchers/prefix object-mode-prefix)
         (value-skip-header (session/message {:op "inspect-toggle-view-mode"})))
    (is+ (matchers/prefix ["--- Contents:" [:newline]
                           "  0. " [:value "2" number?] [:newline]
                           "  1. " [:value "3" number?] [:newline]])
         (value-skip-header (session/message {:op "inspect-push" :idx 13})))))

(deftest display-analytics-integration-test
  (testing "analytics can be displayed with inspect-display-analytics"
    (session/message {:op "inspect-clear"})
    (value-skip-header (session/message {:op      "eval"
                                         :inspect "true"
                                         :code    "(range 100)"}))
    (is+ (matchers/prefix ["--- Analytics:" [:newline]
                           "  " [:value ":count" pos?] " = " [:value "100" pos?] [:newline]
                           "  " [:value ":types" pos?] " = " [:value "{java.lang.Long 100}" pos?] [:newline]])
         (value-skip-header (session/message {:op "inspect-display-analytics"}))))

  (testing "analytics hint is displayed when requested"
    (session/message {:op "inspect-clear"})
    (is+ (matchers/prefix ["--- Analytics:" [:newline] #"Press 'y' or M-x"])
         (value-skip-header (session/message {:op      "eval"
                                              :inspect "true"
                                              :code    "(range 100)"
                                              :display-analytics-hint "true"})))))

(deftest table-view-mode-integration-test
  (testing "table view-mode is supported for lists of maps"
    (session/message {:op "inspect-clear"})
    (session/message {:op      "eval"
                      :inspect "true"
                      :code    "(repeat 20 {:a 1})"})
    (is+ (matchers/prefix table-mode-prefix)
         (value-skip-header (session/message {:op "inspect-toggle-view-mode"})))))

(deftest pretty-print-integration-test
  (testing "renders an object with the pretty printer"
    (session/message {:op "inspect-clear"})
    (session/message {:op      "eval"
                      :inspect "true"
                      :max-coll-size 6
                      :code    "(repeat 5 {:a (repeat 6 {:b 2}) :c (repeat 6 {:d 2})})"})
    (testing "toggle pretty printing and turn it on"
      (is+ ["--- Contents:" [:newline]
            "  0. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 "\n      :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 1]
            [:newline]
            "  1. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 "\n      :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 2]
            [:newline]
            "  2. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 "\n      :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 3]
            [:newline]
            "  3. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 "\n      :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 4]
            [:newline]
            "  4. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 "\n      :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 5]
            [:newline] [:newline]
            #"--- View mode" [:newline]
            "  ●normal table object ●pretty sort-maps"]
           (value-skip-header (session/message {:op "inspect-toggle-pretty-print"}))))
    (testing "toggle pretty printing and turn it off"
      (is+ ["--- Contents:" [:newline]
            "  0. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 " :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 1]
            [:newline]
            "  1. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 " :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 2]
            [:newline]
            "  2. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 " :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 3]
            [:newline]
            "  3. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 " :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 4]
            [:newline]
            "  4. " [:value (str "{:a ({:b 2} {:b 2} {:b 2} {:b 2} {:b 2} {:b 2}),"
                                 " :c ({:d 2} {:d 2} {:d 2} {:d 2} {:d 2} {:d 2})}") 5]
            [:newline] [:newline]
            #"--- View mode" [:newline]
            "  ●normal table object pretty sort-maps"]
           (value-skip-header (session/message {:op "inspect-toggle-pretty-print"}))))))

(deftest print-length-independence-test
  (testing "*print-length* doesn't break rendering of long collections"
    (is (re-find #"showing page: \d+ of \d+"
                 (binding [*print-length* 10]
                   (first (:value (session/message {:op      "eval"
                                                    :inspect "true"
                                                    :code    "(range 100)"}))))))))

(deftest inspect-print-current-value-test
  (testing "inspect-print-current-value returns the currently inspected value as a printed string"
    (is (= [(str/join "\n" ["{:a -1,"
                            " :bb \"111\","
                            " :ccc (1),"
                            " :d"
                            " ({:a 0, :bb \"000\", :ccc ()}"
                            "  {:a -1, :bb \"111\", :ccc (1)}"
                            "  {:a -2, :bb \"222\", :ccc (2 1)}"
                            "  {:a -3, :bb \"333\", :ccc (3 2 1)}"
                            "  {:a -4, :bb \"444\", :ccc (4 3 2 1)})}"])]
           (:value (do
                     (session/message {:op "eval"
                                       :code "(def test-val
                                                (for [i (range 2)]
                                                 {:a (- i)
                                                  :bb (str i i i)
                                                  :ccc (range i 0 -1)
                                                  :d (for [i (range 5)]
                                                       {:a (- i)
                                                        :bb (str i i i)
                                                        :ccc (range i 0 -1)})}))"})
                     (session/message {:op "eval"
                                       :inspect "true"
                                       :code "test-val"})
                     (session/message {:op "inspect-push"
                                       :idx 2})
                     (session/message {:op "inspect-print-current-value"
                                       :nrepl.middleware.print/print "cider.nrepl.pprint/pprint"})))))))

(deftest inspect-print-current-value-no-value-test
  (testing "inspect-print-current-value returns nil if nothing has been inspected yet"
    (is (= ["nil"] (:value (session/message
                            {:op "inspect-print-current-value"
                             :nrepl.middleware.print/print "cider.nrepl.pprint/pprint"}))))))

(deftest inspect-print-current-value-default-print-fn-test
  (testing "inspect-print-current-value uses a default print fn when none is provided"
    (is (= ["nil"] (:value (session/message {:op "inspect-print-current-value"}))))))

(deftest inspect-print-current-value-infinite-seq-test
  (testing "inspect-print-current-value works with infinite-seqs"
    (is (str/starts-with? (first (:value (do (session/message {:op "eval"
                                                               :code "(def test-val (repeat :x))"})
                                             (session/message {:op "eval"
                                                               :inspect "true"
                                                               :code "test-val"})
                                             (session/message {:op "inspect-print-current-value"
                                                               :nrepl.middleware.print/print "cider.nrepl.pprint/pprint"}))))
                          "(:x"))))

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
