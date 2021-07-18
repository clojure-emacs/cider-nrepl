(ns cider.nrepl.middleware.apropos-test
  (:require
   [cider.nrepl.middleware.apropos :refer [apropos] :as apropos]
   [cider.nrepl.test-session :as session]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(def ^:private ^{:doc "Can't. See. Me"} my-private-var [:a :b :c])

(use-fixtures :each session/session-fixture)

(deftest msg->var-query-map-test
  (testing "Constructs the ns-query map correctly"
    (let [msg {:exclude-regexps ["^cider.nrepl" "^refactor-nrepl" "^nrepl"]
               :query "spelling"}
          query-map (#'apropos/msg->var-query-map msg)]
      (is (contains? (:var-query query-map) :ns-query))
      (is (= 3 (count (-> query-map :var-query :ns-query :exclude-regexps))))))

  (testing "No :search key in the query-map if no :query in message"
    (let [msg {:exclude-regexps ["^cider.nrepl" "^refactor-nrepl" "^nrepl"]}
          query-map (#'apropos/msg->var-query-map msg)]
      (is ((complement contains?) (:var-query query-map) :search)))))

(deftest integration-test
  (testing "Apropos op, typical case"
    (let [response (session/message {:op "apropos" :query "handle-apropos"})
          match    (get-in response [:apropos-matches 0])]
      (is (= (:status response) #{"done"}))
      (is (= (:type match) "function"))
      (is (= (:name match) "cider.nrepl.middleware.apropos/handle-apropos"))))

  (testing "Exclude namespaces typical case"
    (let [response (session/message {:op "apropos" :query "handle-apropos"
                                     :exclude-regexps ["cider.nrepl.middleware.apropos"]})
          match    (get-in response [:apropos-matches 0])]
      (is (empty? match))
      (is (= (:status response) #{"done"}))))

  (testing "Apropos op, but specialized cases (invoked with prefix argument)"
    (testing "Fails to get a private var because private? unset"
      (let [response (session/message {:op "apropos" :query "my-private-var"})
            match    (get-in response [:apropos-matches 0])]
        (is (= (:status response) #{"done"}))
        (is (empty? match))))

    (testing "Gets a private var using a case insensitive query"
      (let [response (session/message {:op "apropos" :query "My-Private-Var" :privates? "t"})
            match    (get-in response [:apropos-matches 0])]
        (is (= (:status response) #{"done"}))
        (is (= (:type match) "variable"))
        (is (= (:name match) "cider.nrepl.middleware.apropos-test/my-private-var"))
        (is (= (:doc  match) "Can't."))))

    (testing "Fails to get a private var due to case-mismatch in a case sensitive query"
      (let [response (session/message {:op "apropos"
                                       :query "My-Private-Var"
                                       :privates? "t"
                                       :case-sensitive? "t"})
            match (get-in response [:apropos-matches 0])]
        (is (= (:status response) #{"done"}))
        (is (empty? match))))

    (testing "Finds a public macro via a case-insensitive search through the docs"
      (let [doc-query "threads the expr through the forms"
            response (session/message {:op "apropos" :query doc-query :docs? "t"})
            match    (get-in response [:apropos-matches 0])]
        (is (= (:status response) #{"done"}))
        (is (= (:type match) "macro"))
        (is (= (:name match) "clojure.core/->"))
        (is (-> match ^String (:doc) (.startsWith (str/capitalize doc-query))))))))

(deftest error-handling-test
  (testing "Handles a fake error done via mocked function"
    (with-redefs [apropos
                  (fn [args] (throw (Exception. "boom")))]
      (let [response (session/message {:op "apropos" :query "doesn't matter"})]
        (is (= (:status response) #{"apropos-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: boom")))
        (is (:pp-stacktrace response)))))

  (testing "Handles a real error caused by an improper regular expression"
    (let [response (session/message {:op "apropos" :query "*illegal"})]
      (is (= (:status response) #{"apropos-error" "done"}))
      (is (-> response ^String (:err) (.startsWith "java.util.regex.PatternSyntaxException: Dangling")))
      (is (:pp-stacktrace response)))))
