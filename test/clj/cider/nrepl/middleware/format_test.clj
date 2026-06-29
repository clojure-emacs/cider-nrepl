(ns cider.nrepl.middleware.format-test
  (:require
   [cider.nrepl.middleware.format :as format]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]
   [nrepl.middleware.print :as print])
  (:import
   (java.io File)))

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

(defn shout-formatter
  "A trivial custom formatter used to exercise the `formatter` argument."
  [code _options]
  (.toUpperCase ^String code))

(def not-a-formatter
  "A non-fn var, to exercise the formatter-isn't-a-function guard."
  42)

(deftest configurable-formatter-test
  (testing "a custom formatter is selected by its fully-qualified name"
    (is+ {:status #{"done"}
          :formatted-code "(FOO BAR)"}
         (session/message {:op "cider/format-code"
                           :code "(foo bar)"
                           :formatter "cider.nrepl.middleware.format-test/shout-formatter"})))

  (testing "a missing var in an existing namespace returns a resolve error"
    (is+ {:status #{"cider/format-code-error" "done"}
          :err #"Couldn't resolve formatter cider.nrepl.middleware.format-test/no-such-fn"}
         (session/message {:op "cider/format-code"
                           :code "(foo bar)"
                           :formatter "cider.nrepl.middleware.format-test/no-such-fn"})))

  (testing "a formatter whose namespace can't be loaded returns a load error"
    (is+ {:status #{"cider/format-code-error" "done"}
          :err #"Couldn't load formatter no.such.ns/formatter"}
         (session/message {:op "cider/format-code"
                           :code "(foo bar)"
                           :formatter "no.such.ns/formatter"})))

  (testing "a formatter that isn't a function returns an error"
    (is+ {:status #{"cider/format-code-error" "done"}
          :err #"Formatter cider.nrepl.middleware.format-test/not-a-formatter is not a function"}
         (session/message {:op "cider/format-code"
                           :code "(foo bar)"
                           :formatter "cider.nrepl.middleware.format-test/not-a-formatter"})))

  (testing "the cljfmt default still applies when no formatter is given"
    (is+ {:status #{"done"}
          :formatted-code formatted-code-sample}
         (session/message {:op "cider/format-code" :code ugly-code-sample}))))

(deftest zprint-formatter-test
  ;; zprint isn't a dependency of cider-nrepl, so the built-in adapter can only
  ;; be exercised on its "not available" path here. The happy path is covered by
  ;; projects that put zprint on their own classpath.
  (testing "the built-in zprint formatter reports when zprint is absent"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"zprint is not available on the classpath"
         (format/zprint "(foo bar)" {})))))

(defn- with-temp-config*
  "Write CONTENTS to a temp cljfmt config file, make cljfmt's config lookup
  return it for the duration of THUNK, then clean up."
  [contents thunk]
  (let [tmp (File/createTempFile "cljfmt" ".edn")]
    (try
      (spit tmp contents)
      (with-redefs [format/config-file (constantly tmp)]
        (thunk))
      (finally
        (.delete tmp)))))

(defn- format-with-config [contents msg]
  (with-temp-config* contents #(:formatted-code (format/format-code-reply msg))))

(deftest cljfmt-config-test
  ;; See: https://github.com/clojure-emacs/cider-nrepl/issues/955
  (let [alias-sample "(foo/bar 1\n2)"]
    (testing "indent rules from the project config are applied"
      (is (= "(let [x 3\n      y 4]\n     (+ (* x x) (* y y)))"
             (format-with-config "{:extra-indents {let [[:block 2]]}}"
                                 {:code ugly-code-sample}))))

    (testing ":extra-indents and :alias-map from the project config are applied"
      (is (= "(foo/bar 1\n  2)"
             (format-with-config "{:extra-indents {foo.core/bar [[:inner 0]]}
                                   :alias-map {\"foo\" \"foo.core\"}}"
                                 {:code alias-sample}))))

    (testing "boolean toggles from the project config are respected"
      (is (= "(+ 1 2)   "
             (format-with-config "{:remove-trailing-whitespace? false}"
                                 {:code "(+ 1 2)   "}))))

    (testing "explicit request options take precedence over the project config"
      ;; The config's bogus alias for `foo` would stop the indent rule (keyed on
      ;; `foo.core/bar`) from matching; the request's correct alias makes it apply.
      (is (= "(foo/bar 1\n  2)"
             (format-with-config "{:alias-map {\"foo\" \"wrong.ns\"}
                                   :extra-indents {foo.core/bar [[:inner 0]]}}"
                                 {:code alias-sample
                                  :options {:alias-map {:foo "foo.core"}}}))))

    (testing "formatting works unchanged when there is no project config"
      (is (= formatted-code-sample
             (with-redefs [format/config-file (constantly nil)]
               (:formatted-code (format/format-code-reply {:code ugly-code-sample}))))))

    (testing "a malformed project config surfaces a clear error"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Failed to read cljfmt config"
           (format-with-config "{:indents " {:code "(+ 1 2)"}))))

    (testing "a .clj config is honored but read with eval disabled"
      (let [tmp (File/createTempFile "cljfmt" ".clj")]
        (try
          (with-redefs [format/config-file (constantly tmp)]
            (testing "a plain-data config is applied"
              (spit tmp "{:remove-trailing-whitespace? false}")
              (is (= "(+ 1 2)   "
                     (:formatted-code (format/format-code-reply {:code "(+ 1 2)   "})))))
            (testing "an embedded eval form is rejected, not executed"
              (spit tmp "{:x #=(+ 1 1)}")
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo #"Failed to read cljfmt config"
                   (format/format-code-reply {:code "(+ 1 2)"})))))
          (finally
            (.delete tmp)))))))

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
          :err #"^java.lang.RuntimeException: Unmatched delimiter"
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
