(ns cider.nrepl.middleware.complete-test
  (:require
   [cider.nrepl.middleware.complete :as c]
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as matchers]))

(use-fixtures :each session/session-fixture)

(deftest complete
  (testing "blank"
    (is+ {:status #{"done"}
          :completions (matchers/seq-of map?)}
         (session/message {:op "complete"
                           :ns "user"
                           :prefix ""})))

  (testing "basic usage"
    (is+ {:status #{"done"}
          :completions [{:candidate "filter" :ns "clojure.core" :priority 31}
                        {:candidate "filterv" :ns "clojure.core" :priority 31}]}
         (session/message {:op "complete"
                           :ns "user"
                           :prefix "filt"})))

  (testing "function arglists"
    (is+ {:arglists '("[x y]") :ns "clojure.core", :candidate "unchecked-add", :type "function"}
         (-> (session/message {:op "complete"
                               :ns "user"
                               :prefix "unchecked-a"
                               :extra-metadata ["arglists"]})
             :completions first)))

  (testing "function metadata"
    (is+ {:arglists ["[map key val]" "[map key val & kvs]"]
          :doc string?}
         (first (:completions (session/message {:op "complete"
                                                :ns "user"
                                                :prefix "assoc"
                                                :extra-metadata ["arglists" "doc"]})))))

  (testing "macro metadata"
    (is+ {:arglists ["[name & opts+sigs]"]
          :doc string?}
         (first (:completions (session/message {:op "complete"
                                                :ns "user"
                                                :prefix "defprot"
                                                :extra-metadata ["arglists" "doc"]})))))

  (testing "Clojure 1.12 qualified methods"
    (when (or (> (:major *clojure-version*) 1)
              (>= (:minor *clojure-version*) 12))
      (is+ {:candidate "Thread/.interrupt", :type "method"}
           (first (:completions (session/message {:op "complete"
                                                  :ns "user"
                                                  :prefix "Thread/.int"})))))))

(deftest complete-doc-test
  (testing "blank"
    (is+ {:status #{"done"}
          :completion-doc empty?}
         (session/message {:op "complete-doc" :sym ""})))

  (testing "basic usage"
    (is+ {:status #{"done"}
          :completion-doc #"^clojure.core/true\?"}
         (session/message {:op "complete-doc" :sym "true?"}))))

(deftest complete-flush-caches-test
  (testing "basic usage"
    (let [response (session/message {:op "complete-flush-caches"})]
      (is (= (:status response) #{"done"})))))

(deftest error-handling-test
  (testing "complete op error handling"
    (with-redefs [c/complete (fn [& _] (throw (Exception. "complete-exc")))]
      (is+ {:ex "class java.lang.Exception"
            :status #{"complete-error" "done"}
            :err #"^java.lang.Exception: complete-exc"
            :pp-stacktrace some?}
           (session/message {:op "complete" :ns "doesn't matter" :prefix "fake"}))))

  (testing "complete-doc op error handling"
    (with-redefs [c/completion-doc (fn [& _] (throw (Exception. "complete-doc-exc")))]
      (is+ {:ex "class java.lang.Exception"
            :status #{"complete-doc-error" "done"}
            :err #"^java.lang.Exception: complete-doc-exc"
            :pp-stacktrace some?}
           (session/message {:op "complete-doc" :sym "doesn't matter"})))))
