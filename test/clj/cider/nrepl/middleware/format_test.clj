(ns cider.nrepl.middleware.format-test
  (:require
   [cider.nrepl.middleware.format :as sut]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]
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
    (let [{:keys [formatted-code status]} (session/message {:op "format-code"
                                                            :code ugly-code-sample})]
      (is (= #{"done"} status))
      (is (= formatted-code-sample formatted-code))))

  (testing "format-code works with indents option"
    (let [{:keys [formatted-code status]} (session/message {:op "format-code"
                                                            :code ugly-code-sample
                                                            :options {"indents" {"let" [["block" 2]]}}})]
      (is (= #{"done"} status))
      (is (= "(let [x 3
      y 4]
     (+ (* x x) (* y y)))"
             formatted-code))))

  (testing "format-code works with alias-map option"
    (let [alias-sample    "(foo/bar 1\n2)"
          default-options {"indents" {"foo.core/bar" [["inner" 0]]}}
          normal-reply    (session/message {:op "format-code" :code alias-sample
                                            :options default-options})
          alias-map-reply (session/message {:op "format-code" :code alias-sample
                                            :options (assoc default-options
                                                            "alias-map" {"foo" "foo.core"})})]
      (is (= #{"done"} (:status normal-reply) (:status alias-map-reply)))
      (is (= "(foo/bar 1\n         2)" (:formatted-code normal-reply)))
      (is (= "(foo/bar 1\n  2)" (:formatted-code alias-map-reply)))))

  (testing "format-code op error handling"
    (let [{:keys [status ^String err ex]} (session/message {:op "format-code"
                                                            :code "*/*/*!~v"})]
      (is (= #{"format-code-error" "done"} status))
      (is (.startsWith err "clojure.lang.ExceptionInfo: Invalid"))
      (is (= ex "class clojure.lang.ExceptionInfo"))))

  (testing "format-code returns an error if indents option is invalid"
    (let [{:keys [status ^String err ex] :as reply} (session/message {:op "format-code"
                                                                      :code "(+ 1 2 3)"
                                                                      :options {"indents" "INVALID"}})]
      (is (= #{"format-code-error" "done"} status))
      (is (.startsWith err "java.lang.IllegalArgumentException:"))
      (is (= ex "class java.lang.IllegalArgumentException"))))

  (testing "format-code returns an error if alias-map option is invalid"
    (let [{:keys [status ^String err ex] :as reply} (session/message {:op "format-code"
                                                                      :code "(+ 1 2 3)"
                                                                      :options {"alias-map" "INVALID"}})]
      (is (= #{"format-code-error" "done"} status))
      (is (.startsWith err "java.lang.IllegalArgumentException:"))
      (is (= ex "class java.lang.IllegalArgumentException")))))

(deftest format-edn-op-test
  (testing "format-edn works"
    (let [{:keys [formatted-edn status]} (session/message {:op "format-edn"
                                                           :edn ugly-edn-sample})]
      (is (= formatted-edn-sample formatted-edn))
      (is (= #{"done"} status))))

  ;; See: https://github.com/clojure-emacs/cider-nrepl/issues/722
  (testing "Objects of classes without an associated data-reader function are converted to strings via `pr-str`"
    (let [{:keys [formatted-edn status]
           :as response} (session/message {:op "format-edn"
                                           :edn (pr-str [1 2 (Object.) 3 4])})
          [a b ^String c d e] (read-string formatted-edn)]
      (testing (pr-str response)
        (is (= [1 2 3 4]
               [a b d e]))
        (is (string? c))
        (is (.contains c "java.lang.Object@"))
        (is (= #{"done"} status)))))

  (testing "format-edn works for multiple forms"
    (let [{:keys [formatted-edn status]} (session/message {:op "format-edn"
                                                           :edn ugly-edn-forms-sample})]
      (is (= formatted-edn-forms-sample formatted-edn))
      (is (= #{"done"} status))))

  (testing "format-edn returns an error if the given EDN is malformed"
    (let [{:keys [^String err status] :as response} (session/message {:op "format-edn"
                                                                      :edn unmatched-delimiter-edn-sample})]
      (is (= #{"format-edn-error" "done"} status))
      (is (.startsWith err "clojure.lang.ExceptionInfo: Unmatched delimiter"))
      (is (:pp-stacktrace response))))

  (testing "format-edn respects the :right-margin print config"
    (let [wide-edn-sample     "[1 2 3 4 5 6       7 8     9    0]"
          normal-reply        (session/message {:op  "format-edn" :edn wide-edn-sample})
          narrow-margin-reply (session/message {:op "format-edn"
                                                :edn wide-edn-sample
                                                ::print/print "cider.nrepl.pprint/pprint"
                                                ::print/options {:right-margin 10}})]
      (is (= #{"done"} (:status normal-reply)))
      (is (= "[1 2 3 4 5 6 7 8 9 0]" (:formatted-edn normal-reply)))
      (is (= #{"done"} (:status narrow-margin-reply)))
      (is (= "[1\n 2\n 3\n 4\n 5\n 6\n 7\n 8\n 9\n 0]" (:formatted-edn narrow-margin-reply)))))

  (testing "format-edn respects the ::print/print slot"
    (let [{:keys [formatted-edn status]} (session/message {:op "format-edn"
                                                           :edn "{:b 2 :c 3 :a 1}"
                                                           ::print/print "cider.nrepl.pprint/puget-pprint"})]
      (is (= "{:a 1, :b 2, :c 3}" formatted-edn))
      (is (= #{"done"} status))))

  (testing "format-edn uses a default printer if ::print/print is unresolvable"
    (let [response (-> (session/message {:op "format-edn"
                                         :edn "{:b 2 :c 3 :a 1}"
                                         ::print/print "fake.nrepl.pprint/puget-pprint"})
                       (dissoc :id :session))]
      (is (= {:formatted-edn "{:b 2, :c 3, :a 1}"
              :status #{"done" "nrepl.middleware.print/error"}
              :nrepl.middleware.print/error "Couldn't resolve var fake.nrepl.pprint/puget-pprint"}
             response)))))

(deftest compute-style-indent-test
  (are [macro-name arglists expected] (testing [macro-name arglists]
                                        (is (= expected
                                               (sut/compute-style-indent (str macro-name) arglists)))
                                        true)
    #_macro-name #_arglists              #_expected

    ;; parsing based on the macro name:

    'defprotocol '[[name & opts+sigs]]   [1 [:defn]]
    ;; structurally equivalent:
    'defprotocol '[[nAme & Opts+siGs]]   [1 [:defn]]
    ;; structurally different:
    'defprotocol '[[something-else]]     nil

    ;; parsing based on `&`:

    'anything    '[[& body]]             0
    'anything    '[[a & body]]           1
    'anything    '[[a {} & body]]        2
    'anything    '[[{} & body]]          1
    'anything    '[[{} a & body]]        2
    ;; un-inferrable:
    'anything    '[[a & body], [& body]] nil

    ;; parsing based on argument names:

    'anything    '[[foo]]                nil
    'anything    '[[body]]               0
    'anything    '[[a body]]             1
    'anything    '[[a b body]]           2))
