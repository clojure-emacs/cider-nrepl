(ns cider.nrepl.middleware.cljs-info-test
  (:require [cider.nrepl.piggieback-test :refer [piggieback-fixture]]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(use-fixtures :each piggieback-fixture)

(deftest cljs-info-test
  (let [response (session/message {:op "info"
                                   :ns "cljs.core"
                                   :symbol "map"})]
    (is (= "cljs.core" (:ns response)))
    (is (= "map" (:name response)))
    (is (string? (:arglists-str response)))
    (is (string? (:doc response)))
    (is (string? (:file response)))
    (is (:line response))
    (is (:column response))
    (is (= #{"done"} (:status response))))

  (let [{:keys [status]} (session/message {:op "info"
                                           :ns "cljs.core"
                                           :symbol "non-existent-var"})]
    (is (= #{"no-info" "done"} status))))

(deftest cljs-eldoc-test
  (let [response (session/message {:op "eldoc"
                                   :ns "cljs.core"
                                   :symbol "println"})]
    (is (= [["&" "objs"]] (:eldoc response)))
    (is (= #{"done"} (:status response)))))
