(ns cider.nrepl.middleware.format-test
  (:require
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]
   [nrepl.middleware.print :as print]))

(def ugly-code-sample
  "( let [x 3
       y 4]
  (+ (* x x
        )(* y y)
         ))")

(def formatted-code-sample
  "(let [x 3
      y 4]
  (+ (* x x) (* y y)))")

(def ugly-edn-sample
  "{    :a 1
   :b 2
       :c [0 1 2 3]

          :d [
    [0 1 2 3

      ]
        [0
           1 2 3]]
}")

(def formatted-edn-sample
  "{:a 1, :b 2, :c [0 1 2 3], :d [[0 1 2 3] [0 1 2 3]]}")

(def ugly-edn-forms-sample
  "[
0 1 2 3]

   [0        1 2     3]

[0 1 2

  3

]")

(def formatted-edn-forms-sample
  "[0 1 2 3]
[0 1 2 3]
[0 1 2 3]")

(def unmatched-delimiter-edn-sample
  ")")

(use-fixtures :once session/session-fixture)

(deftest format-code-op-test
  (testing "format-code works"
    (is+ {:status #{"done"}
          :formatted-code formatted-code-sample}
         (session/message {:op "cider/format-code", :code ugly-code-sample})))

  (testing "format-code works with indents option"
    (is+ {:status #{"done"}
          :formatted-code "(let [x 3
      y 4]
     (+ (* x x) (* y y)))"}
         (session/message {:op "cider/format-code"
                           :code ugly-code-sample
                           :options {"indents" {"let" [["block" 2]]}}})))

  (testing "format-code works with alias-map option"
    (let [alias-sample    "(foo/bar 1\n2)"
          default-options {"indents" {"foo.core/bar" [["inner" 0]]}}]
      (is+ {:status #{"done"}
            :formatted-code "(foo/bar 1\n         2)"}
           (session/message {:op "cider/format-code" :code alias-sample
                             :options default-options}))
      (is+ {:status #{"done"}
            :formatted-code "(foo/bar 1\n  2)"}
           (session/message {:op "cider/format-code" :code alias-sample
                             :options (assoc default-options
                                             "alias-map" {"foo" "foo.core"})}))))

  (testing "format-code op error handling"
    (is+ {:status #{"cider/format-code-error" "done"}
          :ex "class clojure.lang.ExceptionInfo"
          :err #"^clojure.lang.ExceptionInfo: Invalid"}
         (session/message {:op "cider/format-code"
                           :code "*/*/*!~v"})))

  (testing "format-code returns an error if indents option is invalid"
    (is+ {:status #{"cider/format-code-error" "done"}}
         (session/message {:op "cider/format-code"
                           :code "(+ 1 2 3)"
                           :options {"indents" "INVALID"}})))

  (testing "format-code returns an error if alias-map option is invalid"
    (is+ {:status #{"cider/format-code-error" "done"}}
         (session/message {:op "cider/format-code"
                           :code "(+ 1 2 3)"
                           :options {"alias-map" "INVALID"}}))))

(deftest format-edn-op-test
  (testing "format-edn works"
    (is+ {:status #{"done"}
          :formatted-edn formatted-edn-sample}
         (session/message {:op "cider/format-edn", :edn ugly-edn-sample})))

  ;; See: https://github.com/clojure-emacs/cider-nrepl/issues/722
  (testing "Objects of classes without an associated data-reader function are converted to strings via `pr-str`"
    (is+ {:status #{"done"}
          :formatted-edn (mc/via read-string [1 2 #"java.lang.Object@" 3 4])}
         (session/message {:op "cider/format-edn"
                           :edn (pr-str [1 2 (Object.) 3 4])})))

  (testing "format-edn works for multiple forms"
    (is+ {:status #{"done"}
          :formatted-edn formatted-edn-forms-sample}
         (session/message {:op "cider/format-edn", :edn ugly-edn-forms-sample})))

  (testing "format-edn returns an error if the given EDN is malformed"
    (is+ {:status #{"cider/format-edn-error" "done"}
          :err #"^clojure.lang.ExceptionInfo: Unmatched delimiter"
          :pp-stacktrace some?}
         (session/message {:op "cider/format-edn"
                           :edn unmatched-delimiter-edn-sample})))

  (testing "format-edn respects the :right-margin print config"
    (let [wide-edn-sample     "[1 2 3 4 5 6       7 8     9    0]"]
      (is+ {:status #{"done"}
            :formatted-edn "[1 2 3 4 5 6 7 8 9 0]"}
           (session/message {:op  "format-edn" :edn wide-edn-sample}))
      (is+ {:status #{"done"}
            :formatted-edn "[1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n 0]"}
           (session/message {:op "cider/format-edn"
                             :edn wide-edn-sample
                             ::print/print "cider.nrepl.pprint/pprint"
                             ::print/options {:right-margin 10}}))))

  (testing "format-edn respects the ::print/print slot"
    (is+ {:status #{"done"}
          :formatted-edn "{:a 1, :b 2, :c 3}"}
         (session/message {:op "cider/format-edn"
                           :edn "{:b 2 :c 3 :a 1}"
                           ::print/print "cider.nrepl.pprint/puget-pprint"})))

  (testing "format-edn uses a default printer if ::print/print is unresolvable"
    (is+ {:status #{"done" "nrepl.middleware.print/error"}
          :formatted-edn "{:b 2, :c 3, :a 1}"
          :nrepl.middleware.print/error "Couldn't resolve var fake.nrepl.pprint/puget-pprint"}
         (session/message {:op "cider/format-edn"
                           :edn "{:b 2 :c 3 :a 1}"
                           ::print/print "fake.nrepl.pprint/puget-pprint"}))))

(deftest deprecated-ops-test
  (testing "Deprecated 'format-code' op still works"
    (is+ {:status #{"done"}
          :formatted-code formatted-code-sample}
         (session/message {:op "format-code", :code ugly-code-sample})))

  (testing "Deprecated 'format-edn' op still works"
    (is+ {:status #{"done"}
          :formatted-edn formatted-edn-sample}
         (session/message {:op "format-edn", :edn ugly-edn-sample}))))
