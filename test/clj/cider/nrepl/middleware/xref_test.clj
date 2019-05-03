(ns cider.nrepl.middleware.xref-test
  (:require
   [cider.nrepl.test-session :as session]
   [cider.nrepl.middleware.xref :refer :all]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(defn- foo [] (map inc (range 10)))

(deftest fn-refs-integration-test
  (let [response (session/message {:op "fn-refs" :ns "clojure.core" :symbol "map"})
        fn-refs (:fn-refs response)]
    (is (= (:status response) #{"done"}))
    (is (> (count fn-refs) 0))
    (is (every? string? fn-refs))))

(deftest fn-deps-integration-test
  (let [response (session/message {:op "fn-deps" :ns "cider.nrepl.middleware.xref-test" :symbol "foo"})
        fn-deps (:fn-deps response)]
    (is (= (:status response) #{"done"}))
    (is (= (count fn-deps) 3))
    (is (every? string? fn-deps))))
