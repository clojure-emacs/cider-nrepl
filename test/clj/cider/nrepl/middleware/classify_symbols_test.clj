(ns cider.nrepl.middleware.classify-symbols-test
  (:require
   [cider.nrepl.test-session :as session]
   [clojure.test :refer :all]))

(use-fixtures :once session/session-fixture)

(deftest classify-symbols-test
  (testing "classifies macros, inline fns, special forms and plain functions"
    (let [{:keys [classification status]}
          (session/message {:op "cider/classify-symbols"
                            :ns "clojure.core"
                            :symbols ["when" "+" "if" "map" "no-such-var-here"]})]
      (is (= #{"done"} status))
      (is (= {:when "macro" :+ "inline" :if "special" :map "function"}
             classification))))

  (testing "resolves namespace-qualified symbols"
    (let [{:keys [classification]}
          (session/message {:op "cider/classify-symbols"
                            :ns "clojure.core"
                            :symbols ["clojure.core/when" "clojure.string/upper-case"]})]
      (is (= {(keyword "clojure.core/when") "macro"
              (keyword "clojure.string/upper-case") "function"}
             classification))))

  (testing "defaults the namespace to user when none is given"
    (let [{:keys [classification]}
          (session/message {:op "cider/classify-symbols"
                            :symbols ["when"]})]
      (is (= {:when "macro"} classification))))

  (testing "omits symbols that don't resolve"
    (let [{:keys [classification]}
          (session/message {:op "cider/classify-symbols"
                            :ns "clojure.core"
                            :symbols ["no-such-var-here"]})]
      (is (empty? classification)))))
