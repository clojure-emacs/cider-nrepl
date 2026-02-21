(ns cider.nrepl.middleware.apropos-test
  (:require
   [cider.nrepl.middleware.apropos :refer [apropos] :as apropos]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]))

(def ^:private ^{:doc "Can't. See. Me"} my-private-var [:a :b :c])

(use-fixtures :each session/session-fixture)

(deftest msg->var-query-map-test
  (testing "Constructs the ns-query map correctly"
    (is+ {:var-query {:ns-query {:exclude-regexps #(= (count %) 3)}}}
         (#'apropos/msg->var-query-map {:exclude-regexps ["^cider.nrepl" "^refactor-nrepl" "^nrepl"]
                                        :query "spelling"})))

  (testing "No :search key in the query-map if no :query in message"
    (is+ {:var-query {:search mc/absent}}
         (#'apropos/msg->var-query-map {:exclude-regexps ["^cider.nrepl" "^refactor-nrepl" "^nrepl"]}))))

(deftest integration-test
  (testing "Apropos op, typical case"
    (is+ {:status #{"done"}
          :apropos-matches [{:type "function"
                             :name "cider.nrepl.middleware.apropos/handle-apropos"}]}
         (session/message {:op "cider/apropos" :query "handle-apropos"})))

  (testing "Exclude namespaces typical case"
    (is+ {:status #{"done"}
          :apropos-matches []}
         (session/message {:op "cider/apropos" :query "handle-apropos"
                           :exclude-regexps ["cider.nrepl.middleware.apropos"]})))

  (testing "Apropos op, but specialized cases (invoked with prefix argument)"
    (testing "Fails to get a private var because private? unset"
      (is+ {:status #{"done"}
            :apropos-matches []}
           (session/message {:op "cider/apropos" :query "my-private-var"})))

    (testing "Gets a private var using a case insensitive query"
      (is+ {:status #{"done"}
            :apropos-matches [{:type "variable"
                               :name "cider.nrepl.middleware.apropos-test/my-private-var"
                               :doc "Can't."}]}
           (session/message {:op "cider/apropos" :query "My-Private-Var" :privates? "t"})))

    (testing "Fails to get a private var due to case-mismatch in a case sensitive query"
      (is+ {:status #{"done"}
            :apropos-matches []}
           (session/message {:op "cider/apropos"
                             :query "My-Private-Var"
                             :privates? "t"
                             :case-sensitive? "t"})))

    (testing "Finds a public macro via a case-insensitive search through the docs"
      (is+ {:status #{"done"}
            :apropos-matches (mc/prefix [{:type "macro"
                                          :name "clojure.core/->"
                                          :doc #"^Threads the expr through the forms"}])}
           (session/message {:op "cider/apropos"
                             :query "threads the expr through the forms"
                             :docs? "t"})))))

(deftest error-handling-test
  (testing "Handles a fake error done via mocked function"
    (with-redefs [apropos
                  (fn [args] (throw (Exception. "boom")))]
      (is+ {:status #{"cider/apropos-error" "done"}
            :ex "class java.lang.Exception"
            :err #"^java.lang.Exception: boom"
            :pp-stacktrace some?}
           (session/message {:op "cider/apropos" :query "doesn't matter"}))))

  (testing "Handles a real error caused by an improper regular expression"
    (is+ {:status #{"cider/apropos-error" "done"}
          :err #"^java.util.regex.PatternSyntaxException: Dangling"
          :pp-stacktrace some?}
         (session/message {:op "cider/apropos" :query "*illegal"}))))

(deftest deprecated-op-test
  (testing "Deprecated 'apropos' op still works"
    (is+ {:status #{"done"}
          :apropos-matches [{:type "function"
                             :name "cider.nrepl.middleware.apropos/handle-apropos"}]}
         (session/message {:op "apropos" :query "handle-apropos"}))))
