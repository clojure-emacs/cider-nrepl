(ns cider.nrepl.middleware.cljs-info-test
  (:require
   [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]))

(use-fixtures :once piggieback-fixture)

(deftest cljs-info-test
  (let [response (session/message {:op "cider/info"
                                   :ns "cljs.core"
                                   :sym "map"})]
    (is (= "cljs.core" (:ns response)))
    (is (= "map" (:name response)))
    (is (string? (:arglists-str response)))
    (is (string? (:doc response)))
    (is (string? (:file response)))
    (is (:line response))
    (is (:column response))
    (is (= #{"done"} (:status response))))

  (let [{:keys [status]} (session/message {:op "cider/info"
                                           :ns "cljs.core"
                                           :sym "non-existent-var"})]
    (is (= #{"no-info" "done"} status))))

(deftest cljs-eldoc-test
  (let [response (session/message {:op "cider/eldoc"
                                   :ns "cljs.core"
                                   :sym "println"})]
    (is (= [["&" "objs"]] (:eldoc response)))
    (is (= #{"done"} (:status response)))))

(deftest cljs-classify-symbols-test
  (testing "classifies macros, special forms and functions in a cljs ns"
    (let [{:keys [classification status]}
          (session/message {:op "cider/classify-symbols"
                            :ns "cljs.core"
                            :symbols ["when" "defn" "if" "map" "no-such-var-here"]})]
      (is (= #{"done"} status))
      ;; `when'/`defn' are cljs.core macros, `if' is a special form, `map' is a
      ;; function; the unresolved symbol is omitted.
      (is (= {:when "macro" :defn "macro" :if "special" :map "function"}
             classification))))

  (testing "classifying leaves the cljs session intact (still resolves as cljs)"
    (let [response (session/message {:op "eval"
                                     :code (nrepl/code (map even? (range 3)))})]
      (is (= "cljs.user" (:ns response)))
      (is (= ["(true false true)"] (:value response)))
      (is (= #{"done"} (:status response))))))
