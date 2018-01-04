(ns cider.nrepl.middleware.info-test
  (:require [clojure.spec.alpha :as s]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(defn ranged-rand
  "Returns random int in range start <= rand < end."
  [start end]
  (+ start (long (rand (- end start)))))

(s/fdef ranged-rand
        :args (s/and (s/cat :start int? :end int?)
                     #(< (:start %) (:end %)))
        :ret int?
        :fn (s/and #(>= (:ret %) (-> % :args :start))
                   #(< (:ret %) (-> % :args :end))))

(use-fixtures :each session/session-fixture)
(deftest integration-test
  (testing "spec info on a normal function with spec"
    (let [response (session/message {:op "info" :symbol "ranged-rand" :ns "cider.nrepl.middleware.info-test"})]
      (is (= (:status response) #{"done"}))
      (is (= (:ns response) "cider.nrepl.middleware.info-test"))
      (is (= (:name response) "ranged-rand"))
      (is (= (:arglists-str response) "[start end]"))
      (is (nil? (:macro response)))
      (is (= (:doc response) "Returns random int in range start <= rand < end."))
      (is (= (:spec response) [["args" "(and\n(cat :start int? :end int?)\n(< (:start %) (:end %)))"]
                               ["ret"  "int?"]
                               ["fn"   "(and\n(>= (:ret %) (-> % :args :start))\n(< (:ret %) (-> % :args :end)))"]]))))
  (testing "spec info on a normal function without a spec"
      ;; spec is not defined for this function
      (let [response (session/message {:op "info" :symbol "same-name-testing-function" :ns "cider.test-ns.first-test-ns"})]
        (is (= (:status response) #{"done"}))
        (is (= (:ns response) "cider.test-ns.first-test-ns"))
        (is (= (:name response) "same-name-testing-function"))
        (is (= (:arglists-str response) "[]"))
        (is (nil? (:macro response)))
        (is (= (:doc response) "Multiple vars with the same name in different ns's. Used to test ns-list-vars-by-name."))
        (is (nil? (:spec response)))))

  (testing "spec info on clojure.core/let"
    (let [response (session/message {:op "info" :symbol "let" :ns "cider.nrepl.middleware.info-test"})]
      (is (= (:status response) #{"done"}))
      (is (= (:ns response) "clojure.core"))
      (is (= (:name response) "let"))
      (is (not-empty (:spec response))))))
