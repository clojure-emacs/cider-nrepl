(ns cider.nrepl.middleware.xref-test
  (:require
   [cider.nrepl.test-session :as session]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(defn- foo [] (map inc (range 10)))

(deftest fn-refs-integration-test
  (let [response (session/message {:op "cider/fn-refs" :ns "clojure.core" :sym "map"})
        fn-refs (:fn-refs response)]
    (testing (pr-str response)
      (is (= (:status response) #{"done"}))
      (is (> (count fn-refs) 0))
      (is (every? map? fn-refs))))

  (testing "`:file-url`"
    (let [response (session/message {:op "cider/fn-refs"
                                     :ns "cider.nrepl.middleware.xref"
                                     :sym "fn-refs-reply"})
          x (->> response
                 :fn-refs
                 (map :file-url))]
      (assert (= (:status response) #{"done"})
              (pr-str response))
      (assert (seq x))
      (doseq [i x]
        (is (or (str/starts-with? i "file:")
                (str/starts-with? i "jar:file:")))))))

(deftest fn-deps-integration-test
  (let [response (session/message {:op "cider/fn-deps" :ns "cider.nrepl.middleware.xref-test" :sym "foo"})
        fn-deps (:fn-deps response)]
    (is (= (:status response) #{"done"}))
    (is (= (count fn-deps) 3))
    (is (every? map? fn-deps)))

  (testing "`:file-url`"
    (let [x (->> {:op "cider/fn-deps"
                  :ns "cider.nrepl.middleware.xref"
                  :sym "fn-refs-reply"
                  :file-format "v2"}
                 session/message
                 :fn-deps
                 (map :file-url))]
      (assert (seq x))
      (doseq [i x]
        (is (or (str/starts-with? i "file:")
                (str/starts-with? i "jar:file:")))))))

(deftest deprecated-ops-test
  (testing "Deprecated 'fn-refs' op still works"
    (let [response (session/message {:op "fn-refs" :ns "clojure.core" :sym "map"})
          fn-refs (:fn-refs response)]
      (is (= (:status response) #{"done"}))
      (is (> (count fn-refs) 0))))

  (testing "Deprecated 'fn-deps' op still works"
    (let [response (session/message {:op "fn-deps" :ns "cider.nrepl.middleware.xref-test" :sym "foo"})
          fn-deps (:fn-deps response)]
      (is (= (:status response) #{"done"}))
      (is (= (count fn-deps) 3)))))

;;; who-implements

(defprotocol TestProto
  (test-m [_]))

(defrecord TestImpl [x]
  TestProto
  (test-m [_] x))

(deftest who-implements-protocol-test
  (let [response (session/message {:op "cider/who-implements"
                                   :ns "cider.nrepl.middleware.xref-test"
                                   :sym "TestProto"})
        result (:who-implements response)]
    (testing (pr-str response)
      (is (= (:status response) #{"done"}))
      (is (= "protocol" (:kind result)))
      (is (some #(= "cider.nrepl.middleware.xref_test.TestImpl" (:name %))
                (:impls result))
          "includes the inline defrecord implementer"))))

(deftest who-implements-multimethod-test
  (let [response (session/message {:op "cider/who-implements"
                                   :ns "clojure.core"
                                   :sym "print-method"})
        result (:who-implements response)]
    (testing (pr-str response)
      (is (= (:status response) #{"done"}))
      (is (= "multimethod" (:kind result)))
      (is (seq (:dispatch-values result))))))

(deftest who-implements-other-test
  (let [response (session/message {:op "cider/who-implements"
                                   :ns "clojure.core"
                                   :sym "map"})
        result (:who-implements response)]
    (testing (pr-str response)
      (is (= (:status response) #{"done"}))
      (is (= "other" (:kind result))))))

(deftest type-protocols-integration-test
  (let [response (session/message {:op "cider/type-protocols"
                                   :ns "cider.nrepl.middleware.xref-test"
                                   :sym "TestImpl"})
        names (set (map :name (:type-protocols response)))]
    (testing (pr-str response)
      (is (= (:status response) #{"done"}))
      (is (some #(str/includes? % "TestProto") names)
          "lists the protocol the record implements"))))

(deftest protocols-with-method-integration-test
  (let [response (session/message {:op "cider/protocols-with-method"
                                   :method "test-m"})
        names (set (map :name (:protocols-with-method response)))]
    (testing (pr-str response)
      (is (= (:status response) #{"done"}))
      (is (some #(str/includes? % "TestProto") names)
          "lists the protocol declaring the method"))))
