(ns cider.nrepl.middleware.doc-test
  (:use
   [clojure.test]
   [clojure.repl :only [doc]]
   [cider.nrepl.middleware.doc]
   [cider.nrepl.middleware.test-transport :only [test-transport messages]]))

(deftest doc-test
  (testing "symbol not found"
    (let [t (test-transport)]
      (doc-response {:transport t
                     :symbol "this-doesnt-exit"
                     :ns "clojure.core"})
      (is (= [{:status #{:not-found}}]
             (messages t)))))
  (testing "symbol found"
    (let [t (test-transport)]
      (doc-response {:transport t
                     :symbol "str"
                     :ns "clojure.core"})
      (is (= [{:doc (with-out-str (doc str))}
              {:status #{:done}}]
             (messages t))))))

(deftest docstring-test
  (testing "symbol not found"
    (let [t (test-transport)]
      (docstring-response {:transport t
                           :symbol "this-doesnt-exit"
                           :ns "clojure.core"})
      (is (= [{:status #{:not-found}}]
             (messages t)))))
  (testing "symbol found"
    (let [t (test-transport)]
      (docstring-response {:transport t
                           :symbol "str"
                           :ns "clojure.core"})
      (is (= [{:docstring (-> #'str meta :doc)}
              {:status #{:done}}]
             (messages t))))))
