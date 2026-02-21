(ns cider.nrepl.middleware.classpath-test
  (:require
   [cider.nrepl.test-session :as session]
   [cider.nrepl.middleware.classpath :refer :all]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]))

(use-fixtures :each session/session-fixture)

(deftest integration-test
  (is+ {:status #{"done"}
        :classpath (mc/all-of #(> (count %) 1)
                              (mc/seq-of string?)
                              (mc/embeds [#"clojure-.*jar"]))}
       (session/message {:op "cider/classpath"})))

(deftest error-handling-test
  (with-redefs [classpath-reply (fn [_] (throw (Exception. "cp error")))]
    (is+ {:status #{"done" "cider/classpath-error"}
          :ex "class java.lang.Exception"
          :err #"^java.lang.Exception: cp error"
          :pp-stacktrace some?}
         (session/message {:op "cider/classpath"}))))

(deftest deprecated-op-test
  (testing "Deprecated 'classpath' op still works"
    (is+ {:status #{"done"}
          :classpath seq}
         (session/message {:op "classpath"}))))

(deftest file-url?-test
  (is (file-url? (.toURL (.toURI (java.io.File. "")))))
  (is (not (file-url? (.toURL (java.net.URI. "jar:file:/tmp/test.jar!/BOOT-INF/classes"))))))
