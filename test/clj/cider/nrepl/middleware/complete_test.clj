(ns cider.nrepl.middleware.complete-test
  (:require [cider.nrepl.test-session :as session]
            [cider.nrepl.test-transport :refer [messages test-transport]]
            [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(deftest complete
  (let [response (session/message {:op "complete"
                                   :ns "user"
                                   :symbol ""})]
    (is (= #{"done"} (:status response)))
    (is (sequential? (:completions response)))
    (is (every? map? (:completions response))))

  (testing "function arglists"
    (let [response (session/message {:op "complete"
                                     :ns "user"
                                     :symbol "unchecked-a"
                                     :extra-metadata ["arglists"]})]
      (is (= {:arglists '("[x y]") :ns "clojure.core", :candidate "unchecked-add", :type "function"}
             (first (:completions response))))))

  (testing "function metadata"
    (let [response (session/message {:op "complete"
                                     :ns "user"
                                     :symbol "assoc"
                                     :extra-metadata ["arglists" "doc"]})
          candidate (first (:completions response))]
      (is (= '("[map key val]" "[map key val & kvs]") (:arglists candidate)))
      (is (string? (:doc candidate)))))

  (testing "macro metadata"
    (let [response (session/message {:op "complete"
                                     :ns "user"
                                     :symbol "defprot"
                                     :extra-metadata ["arglists" "doc"]})
          candidate (first (:completions response))]
      (is (= '("[name & opts+sigs]") (:arglists candidate)))
      (is (string? (:doc candidate))))))
