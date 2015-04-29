(ns cider.nrepl.middleware.cljs-complete-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each piggieback-fixture)

(deftest cljs-complete
  (let [response (session/message {:op "complete"
                                   :ns "cljs.user"
                                   :symbol ""})]
    (is (= #{"done"} (:status response)))
    (is (sequential? (:completions response)))
    (is (every? map? (:completions response))))

  (let [response (session/message {:op "complete"
                                   :ns "cljs.user"
                                   :symbol "defpro"})
        candidate (first (:completions response))]
    (is (= "defprotocol" (:candidate candidate)))
    (is (= "cljs.core" (:ns candidate)))
    (is (= "macro" (:type candidate))))

  (testing "function metadata"
    (let [response (session/message {:op "complete"
                                     :ns "cljs.user"
                                     :symbol "assoc"
                                     :extra-metadata ["arglists" "doc"]})
          candidate (first (:completions response))]
      (is (= '("[coll k v]" "[coll k v & kvs]") (:arglists candidate)))
      (is (string? (:doc candidate)))))

  (testing "macro metadata"
    (let [response (session/message {:op "complete"
                                     :ns "cljs.user"
                                     :symbol "defprot"
                                     :extra-metadata ["arglists" "doc"]})
          candidate (first (:completions response))]
      (is (= '("[psym & doc+methods]") (:arglists candidate)))
      (is (string? (:doc candidate))))))
