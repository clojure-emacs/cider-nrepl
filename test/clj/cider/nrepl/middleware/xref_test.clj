(ns cider.nrepl.middleware.xref-test
  (:require
   [cider.nrepl.test-session :as session]
   [clojure.string :as string]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(defn- foo [] (map inc (range 10)))

(deftest fn-refs-integration-test
  (let [response (session/message {:op "fn-refs" :ns "clojure.core" :sym "map"})
        fn-refs (:fn-refs response)]
    (testing (pr-str response)
      (is (= (:status response) #{"done"}))
      (is (> (count fn-refs) 0))
      (is (every? map? fn-refs))))

  (testing "`:file-url`"
    (let [response (session/message {:op "fn-refs"
                                     :ns "cider.nrepl.middleware.xref"
                                     :sym "fn-refs-reply"})
          x (->> response
                 :fn-refs
                 (map :file-url))]
      (assert (= (:status response) #{"done"})
              (pr-str response))
      (assert (seq x))
      (doseq [i x]
        (is (or (string/starts-with? i "file:")
                (string/starts-with? i "jar:file:")))))))

(deftest fn-deps-integration-test
  (let [response (session/message {:op "fn-deps" :ns "cider.nrepl.middleware.xref-test" :sym "foo"})
        fn-deps (:fn-deps response)]
    (is (= (:status response) #{"done"}))
    (is (= (count fn-deps) 3))
    (is (every? map? fn-deps)))

  (testing "`:file-url`"
    (let [x (->> {:op "fn-deps"
                  :ns "cider.nrepl.middleware.xref"
                  :sym "fn-refs-reply"
                  :file-format "v2"}
                 session/message
                 :fn-deps
                 (map :file-url))]
      (assert (seq x))
      (doseq [i x]
        (is (or (string/starts-with? i "file:")
                (string/starts-with? i "jar:file:")))))))
