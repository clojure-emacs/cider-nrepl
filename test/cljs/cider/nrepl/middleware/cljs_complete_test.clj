(ns cider.nrepl.middleware.cljs-complete-test
  (:require
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :once piggieback-fixture)

(deftest cljs-complete-test
  (let [response (session/message {:op "complete"
                                   :symbol ""})]
    (is (= #{"done"} (:status response)))
    (is (sequential? (:completions response)))
    (is (every? map? (:completions response))))

  (let [response (session/message {:op "complete"
                                   :symbol "defpro"})
        candidate (first (:completions response))]
    (is (= "defprotocol" (:candidate candidate)))
    (is (= "cljs.core" (:ns candidate)))
    (is (= "macro" (:type candidate))))

  (testing "function metadata"
    (let [response (session/message {:op "complete"
                                     :symbol "assoc"
                                     :extra-metadata ["arglists" "doc"]})
          candidate (first (:completions response))]
      (is (= '("[coll k v]" "[coll k v & kvs]") (:arglists candidate)))
      (is (string? (:doc candidate)))))

  (testing "macro metadata"
    (let [response (session/message {:op "complete"
                                     :symbol "defprot"
                                     :extra-metadata ["arglists" "doc"]})
          candidate (first (:completions response))]
      (is (= '("[psym & doc+methods]") (:arglists candidate)))
      (is (string? (:doc candidate))))))

(deftest cljs-complete-with-suitable-test
  (testing "js global completion"
    (let [response (session/message {:op "complete"
                                     :ns "cljs.user"
                                     :symbol "js/Ob"
                                     :enhanced-cljs-completion? "t"})
          candidates (:completions response)]
      (is (= [{:candidate "js/Object", :ns "js", :type "function"}] candidates))))

  (testing "manages context state"
    (session/message {:op "complete"
                      :ns "cljs.user"
                      :symbol ".xxxx"
                      :context "(__prefix__ js/Object)"
                      :enhanced-cljs-completion? "t"})
    (let [response (session/message {:op "complete"
                                     :ns "cljs.user"
                                     :symbol ".key"
                                     :context ":same"
                                     :enhanced-cljs-completion? "t"})
          candidates (:completions response)]
      (is (= [{:ns "js/Object", :candidate ".keys" :type "function"}] candidates))))

  (testing "no suitable completions without enhanced-cljs-completion? flag"
    (let [response (session/message {:op "complete"
                                     :ns "cljs.user"
                                     :symbol "js/Ob"})
          candidates (:completions response)]
      (is (empty? candidates)))))

(deftest cljs-complete-doc-test
  (testing "no suitable documentation can be found"
    (let [response (session/message {:op "complete-doc" :symbol "tru"})]
      (is (= (:status response) #{"done"}))
      (is (empty? (:completion-doc response)) "an unknown symbol should have empty doc.")))

  (testing "suitable documentation for a symbol"
    (let [response (session/message {:op "complete-doc" :symbol "map"})]
      (is (= (:status response) #{"done"}))
      (is (re-find #"\(\[f\] \[f coll\] \[f c1 c2\] \[f c1 c2 c3\] \[f c1 c2 c3 & colls\]\)" (:completion-doc response)) "should return the \"map\" docstring")))

  (testing "suitable documentation for a macro symbol"
    (let [response (session/message {:op "complete-doc" :symbol "when"})]
      (is (= (:status response) #{"done"}))
      (is (re-find #"\(\[test & body\]\)" (:completion-doc response)) "should return the \"when\" docstring"))))
