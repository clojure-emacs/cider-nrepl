(ns cider.nrepl.middleware.complete-test
  (:require
   [cider.nrepl.middleware.complete :as c]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest complete
  (testing "blank"
    (let [response (session/message {:op "complete"
                                     :ns "user"
                                     :prefix ""})]
      (is (= #{"done"} (:status response)))
      (is (sequential? (:completions response)))
      (is (every? map? (:completions response)))))

  (testing "basic usage"
    (let [response (session/message {:op "complete"
                                     :ns "user"
                                     :prefix "filt"})]
      (is (= #{"filter" "filterv"} (->> response
                                        :completions
                                        (map :candidate)
                                        set)))

      (is (= #{"clojure.core"} (->> response
                                    :completions
                                    (map :ns)
                                    set)))

      (is (= #{"done"} (:status response)))))

  (testing "function arglists"
    (let [response (session/message {:op "complete"
                                     :ns "user"
                                     :prefix "unchecked-a"
                                     :extra-metadata ["arglists"]})]
      (is (= {:arglists '("[x y]") :ns "clojure.core", :candidate "unchecked-add", :type "function"}
             (first (:completions response))))))

  (testing "function metadata"
    (let [response (session/message {:op "complete"
                                     :ns "user"
                                     :prefix "assoc"
                                     :extra-metadata ["arglists" "doc"]})
          candidate (first (:completions response))]
      (is (= '("[map key val]" "[map key val & kvs]") (:arglists candidate)))
      (is (string? (:doc candidate)))))

  (testing "macro metadata"
    (let [response (session/message {:op "complete"
                                     :ns "user"
                                     :prefix "defprot"
                                     :extra-metadata ["arglists" "doc"]})
          candidate (first (:completions response))]
      (is (= '("[name & opts+sigs]") (:arglists candidate)))
      (is (string? (:doc candidate))))))

(deftest complete-doc-test
  (testing "blank"
    (let [response (session/message {:op "complete-doc" :sym ""})]
      (is (= #{"done"} (:status response)))
      (is (empty? (:completion-doc response)))))

  (testing "basic usage"
    (let [response (session/message {:op "complete-doc" :sym "true?"})]
      (is (= (:status response) #{"done"}))
      (is (-> response ^String (:completion-doc) (.startsWith "clojure.core/true?\n([x"))))))

(deftest complete-flush-caches-test
  (testing "basic usage"
    (let [response (session/message {:op "complete-flush-caches"})]
      (is (= (:status response) #{"done"})))))

(deftest error-handling-test
  (testing "complete op error handling"
    (with-redefs [c/complete (fn [& _] (throw (Exception. "complete-exc")))]
      (let [response (session/message {:op "complete" :ns "doesn't matter" :prefix "fake"})]
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"complete-error" "done"}))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: complete-exc")))
        (is (:pp-stacktrace response)))))

  (testing "complete-doc op error handling"
    (with-redefs [c/completion-doc (fn [& _] (throw (Exception. "complete-doc-exc")))]
      (let [response (session/message {:op "complete-doc" :sym "doesn't matter"})]
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"complete-doc-error" "done"}))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: complete-doc-exc")))
        (is (:pp-stacktrace response))))))
