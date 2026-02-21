(ns cider.nrepl.middleware.info-spec-test
  (:require
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [cider.test-ns.first-test-ns]
   [cider.test-ns.second-test-ns]
   [cider.test-ns.third-test-ns]
   [clojure.spec.alpha :as s]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]))

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
    (is+ {:status #{"done"}
          :ns "cider.nrepl.middleware.info-spec-test"
          :name "ranged-rand"
          :arglists-str "[start end]"
          :macro mc/absent
          :doc "Returns random int in range start <= rand < end."
          :spec  ["clojure.spec.alpha/fspec"
                  ":args" ["clojure.spec.alpha/and"
                           ["clojure.spec.alpha/cat" ":start" "clojure.core/int?" ":end" "clojure.core/int?"]
                           ["clojure.core/fn" ["%"] ["clojure.core/<" [":start" "%"] [":end" "%"]]]]
                  ":ret" "clojure.core/int?"
                  ":fn" ["clojure.spec.alpha/and"
                         ["clojure.core/fn" ["%"] ["clojure.core/>=" [":ret" "%"] ["clojure.core/->" "%" ":args" ":start"]]]
                         ["clojure.core/fn" ["%"] ["clojure.core/<" [":ret" "%"] ["clojure.core/->" "%" ":args" ":end"]]]]]}
         (session/message {:op "info" :sym "ranged-rand" :ns "cider.nrepl.middleware.info-spec-test"})))

  (testing "same name testing function without a spec"
    ;; spec is not defined for this function
    (is+ {:status #{"done"}
          :ns "cider.test-ns.first-test-ns"
          :name "same-name-testing-function"
          :arglists-str "[]"
          :macro mc/absent
          :doc "Multiple vars with the same name in different ns's. Used to test ns-list-vars-by-name."
          :spec mc/absent}
         (session/message {:op "info" :sym "same-name-testing-function" :ns "cider.test-ns.first-test-ns"})))

  (testing "spec info on clojure.core/let"
    (is+ {:status #{"done"}
          :ns "clojure.core"
          :name "let"
          :spec ["clojure.spec.alpha/fspec"
                 ":args" ["clojure.spec.alpha/cat"
                          ":bindings" ":clojure.core.specs.alpha/bindings"
                          ":body" ["clojure.spec.alpha/*" "clojure.core/any?"]]
                 ":ret" "clojure.core/any?" ":fn" ""]}
         (session/message {:op "info" :sym "let" :ns "cider.nrepl.middleware.info-spec-test"}))))
