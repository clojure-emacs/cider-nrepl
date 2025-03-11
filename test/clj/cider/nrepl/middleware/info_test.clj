(ns cider.nrepl.middleware.info-test
  (:require
   [cider.nrepl.middleware.info :as info]
   [cider.nrepl.test-session :as session]
   [clojure.data]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as matchers]
   [matcher-combinators.test :refer [match?]]
   [orchard.java.source-files]
   [orchard.misc :as misc])
  (:import (cider.nrepl.test AnotherTestClass TestClass YetAnotherTest)))

(defprotocol FormatResponseTest
  (proto-foo [this])
  (proto-bar [this] "baz"))

(def jdk-sources-present?
  "Is Java sources files available on the classpath?"
  (boolean (orchard.java.source-files/class->source-file-url Thread)))

(deftest format-response-test
  (is (match? {"javadoc" #"^(https?|file|jar|zip):"} ; resolved either locally or online
              (-> (info/info {:class "java.lang.Object" :member "toString"})
                  (info/format-response))))

  (is (info/format-response (info/info {:ns "cider.nrepl.middleware.info" :sym "clojure.core"})))

  (is (-> (info/info {:ns "cider.nrepl.middleware.info" :sym "clojure.core"})
          (dissoc :file)
          (info/format-response)))

  (is (info/format-response (info/info {:ns "cider.nrepl.middleware.info" :sym "clojure.core//"})))
  (is (info/format-response (info/info {:ns "cider.nrepl.middleware.info" :sym "clojure.core/+"})))
  ;; used to crash, sym is parsed as a class name
  (is (nil? (info/format-response (info/info {:ns "cider.nrepl.middleware.info" :sym "notincanter.core"}))))
  ;; unfound nses should fall through
  (is (nil? (info/format-response (info/info {:ns "cider.nrepl.middleware.nonexistent-namespace" :sym "a-var"}))))
  ;; protocol docstring
  (is (match? {"doc" matchers/absent}
              (info/format-response (info/info {:ns "cider.nrepl.middleware.info-test" :sym "proto-foo"}))))
  (is (match? {"doc" "baz"}
              (info/format-response (info/info {:ns "cider.nrepl.middleware.info-test" :sym "proto-bar"})))))

(deftest response-test
  (let [v (ns-resolve 'cider.nrepl.middleware.info 'assoc)
        {:keys [arglists column line added static doc]} (meta v)]
    (is (= {"ns" "clojure.core"
            "name" "assoc"
            "arglists-str" (->> (map pr-str arglists)
                                (str/join \newline))
            "column" column
            "added" added
            "static" (str static)
            "doc" doc
            "line" line
            "resource" "clojure/core.clj"}
           (-> {:ns "cider.nrepl.middleware.info" :sym "assoc"}
               info/info
               info/format-response
               (dissoc "file" "see-also"))))))

(defn var-with-custom-meta
  {:custom/meta 1}
  [foo]
  :bar)

(deftest info-test
  ;; handle zero-length input
  (is (nil? (info/info {:ns (str (ns-name *ns*)) :sym ""})))
  (is (nil? (info/info {:ns "" :sym ""})))
  (is (nil? (info/info {:ns "cider.nrepl.middleware.info-test"
                        :class "Thread"})))

  (is (nil? (info/info {:class "java.lang.Thread" :member "false123"})))

  (is (nil? (info/info {:sym "ns-resolve"}))
      "A `:ns` is needed for Clojure queries")

  (is (contains? (info/info {:ns (-> ::_ namespace str)
                             :sym "ns-resolve"})
                 :doc))

  (testing "`:var-meta-allowlist`"
    (let [base-keys [:ns :name :file :arglists :line :column]]
      (is (= base-keys (keys (info/info {:ns (-> ::_ namespace str)
                                         :sym "var-with-custom-meta"}))))
      (is (= (conj base-keys :custom/meta)
             (keys (info/info {:ns (-> ::_ namespace str)
                               :sym "var-with-custom-meta"
                               :var-meta-allowlist [:custom/meta]}))))))

  (is (= (info/info {:ns (-> ::_ namespace str)
                     :sym "ns-resolve"})
         (info/info {:ns (-> ::_ namespace str)
                     :context "(let [v \"\"] \n (__prefix__ v))"
                     :sym "ns-resolve"}))
      "The context is ignored for non-Java queries")

  (is (nil? (info/info {:class "Thread" :member "checkAccess"}))
      "A non-fully qualified class won't be resolved")
  (is (some? (info/info {:class "java.lang.Thread" :member "checkAccess"})))
  (is (some? (info/info {:ns (-> ::_ namespace str) :class "Thread" :member "checkAccess"}))
      "A `:ns` can help resolving a non-fully qualified class")
  (let [false-ns "a123"]
    (remove-ns (symbol false-ns))
    (is (nil? (info/info {:ns false-ns :class "Thread" :member "checkAccess"}))
        "A non-existing ns won't help resolving a non-fully qualified class"))

  (testing "A `:context` can disambiguate input, reducing the `:candidates` to just one"
    (is (match? {:candidates {'java.lang.String some?
                              'java.lang.StringBuffer some?
                              'java.lang.StringBuilder some?}}
                (info/info {:ns (str *ns*) :symbol ".codePointCount"})))
    (is (match? {:candidates matchers/absent
                 :class `String}
                (info/info {:ns (str *ns*) :symbol ".codePointCount" :context "(let [v \"\"] \n (__prefix__ v))"})))
    (is (match? {:added "1.0", :ns 'clojure.core, :name '.., :file "clojure/core.clj"}
                (info/info {:ns (str *ns*) :symbol ".." :context "(let [v \"\"] \n (__prefix__ v))"}))
        "The context is ignored for the special form `..`")))

;; Used below in an integration test
(def ^{:protocol #'clojure.data/Diff} junk-protocol-client nil)

(defn testing-function
  "This is used for testing"
  [a b c]
  (+ a b c))

(defmacro testing-macro
  "a macro for testing"
  [pred a b]
  `(if (not ~pred) ~a ~b))

(use-fixtures :each session/session-fixture)
(deftest integration-test
  (testing "info op"
    (testing "get info of a clojure function"
      (is (match? {:status       #{"done"}
                   :ns           "cider.nrepl.middleware.info-test"
                   :name         "testing-function"
                   :arglists-str "[a b c]"
                   :macro        matchers/absent
                   :doc          "This is used for testing"
                   :spec         matchers/absent}
                  (session/message {:op "info" :sym "testing-function" :ns "cider.nrepl.middleware.info-test"}))))

    (testing "get info of a clojure macro"
      (is (match? {:status       #{"done"}
                   :ns           "cider.nrepl.middleware.info-test"
                   :name         "testing-macro"
                   :arglists-str "[pred a b]"
                   :macro        "true"
                   :doc          "a macro for testing"
                   :spec         matchers/absent}
                  (session/message {:op "info" :sym "testing-macro" :ns "cider.nrepl.middleware.info-test"}))))

    (when jdk-sources-present?
      (testing "'fragments' attributes are returned"
        (let [{:keys [doc-fragments doc-first-sentence-fragments doc-block-tags-fragments]
               :as response}
              (session/message {:op "info"
                                :class "java.lang.Thread"
                                :member "sleep"})]
          (testing (pr-str response)
            (doseq [f [doc-fragments doc-first-sentence-fragments doc-block-tags-fragments]]
              (is (vector? f))
              (is (map? (first f))))
            (is (= #{"done"} (:status response)))))))

    (testing "get info of a java instance method with return value"
      (is (match? {:status       #{"done"}
                   :class        "cider.nrepl.test.TestClass"
                   :member       "getInt"
                   :arglists-str "[this]"
                   :argtypes     []
                   :returns      "int"
                   :modifiers    "#{:public}"
                   :javadoc      (if (= misc/java-api-version 8)
                                   "cider/nrepl/test/TestClass.html#getInt--"
                                   "cider/nrepl/test/TestClass.html#getInt()")}
                  (session/message {:op "info"
                                    :class "cider.nrepl.test.TestClass"
                                    :member "getInt"}))))

    (testing "get info of a private java class method, void return"
      (is (match? {:status       #{"done"}
                   :class        "cider.nrepl.test.TestClass"
                   :member       "doSomething"
                   ;; Java source parsing is only supported in JDK11+.
                   :arglists-str (if (> misc/java-api-version 8)
                                   "[a b c]" some?)
                   :argtypes     ["int" "int" "java.lang.String"]
                   :returns      "void"
                   :modifiers    "#{:private :static}"
                   :javadoc      (if (= misc/java-api-version 8)
                                   "cider/nrepl/test/TestClass.html#doSomething-int-int-java.lang.String-"
                                   "cider/nrepl/test/TestClass.html#doSomething(int,int,java.lang.String)")}
                  (session/message {:op "info"
                                    :class "cider.nrepl.test.TestClass"
                                    :member "doSomething"}))))

    (testing "get info of a java method"
      (is (match? {:status       #{"done"}
                   :class        "java.lang.StringBuilder"
                   :member       "capacity"
                   :arglists-str "[this]"
                   :argtypes     []
                   :returns      "int"
                   :modifiers    "#{:public}"
                   :javadoc      (if (= misc/java-api-version 8)
                                   "https://docs.oracle.com/javase/8/docs/api/java/lang/StringBuilder.html#capacity--"
                                   #"https://docs.oracle.com/en/java/javase/\d+/docs/api/java.base/java/lang/StringBuilder.html#capacity()")}
                  (session/message {:op "info"
                                    :class "java.lang.StringBuilder"
                                    :member "capacity"}))))

    (testing "get info on the dot-operator"
      (is (match? {:status       #{"done"}
                   :name         "."
                   :url          "https://clojure.org/java_interop#dot"
                   :special-form "true"
                   :doc          #"^The instance member form works"
                   :forms-str    #"^\(\.instanceMember instance args\*\)\n\(\.instanceMember"}
                  (session/message {:op "info" :sym "." :ns "user"}))))

    (testing "get info of a clojure non-core file, located in a jar"
      (is (match? {:status       #{"done"}
                   :name         "resource"
                   :resource     "clojure/java/io.clj"
                   :ns           "clojure.java.io"
                   :arglists-str "[n]\n[n loader]"
                   :doc          #"^Returns the URL for a named"
                   :file         #"^jar:file:"}
                  (session/message {:op "info" :sym "resource" :ns "clojure.java.io"}))))

    (testing "nested members"
      (let [response   (session/message {:op "info" :ns (ns-name *ns*) :sym ".toString"})
            candidates (:candidates response)
            individual (:java.lang.Exception candidates)]
        (is (match? {:candidates
                     {:java.lang.NoSuchFieldException some?
                      :java.lang.Package some?
                      :java.lang.LinkageError some?
                      :java.lang.Exception {:throws []
                                            :member "toString"
                                            :modifiers "#{:public}"}}}
                    response))))

    (testing "get protocol info"
      (is (match? {:status   #{"done"}
                   :name     "junk-protocol-client"
                   :file     #"clojure/data.clj$"
                   :protocol "#'clojure.data/Diff"
                   :ns       "cider.nrepl.middleware.info-test"}
                  (session/message {:op "info"
                                    :ns "cider.nrepl.middleware.info-test"
                                    :sym "junk-protocol-client"}))))

    (testing "see also"
      (is (match? {:see-also ["clojure.core/map-indexed" "clojure.core/pmap" "clojure.core/amap" "clojure.core/mapcat" "clojure.core/keep" "clojure.core/juxt" "clojure.core/mapv" "clojure.core/reduce" "clojure.core/run!"]}
                  (session/message {:op "info" :sym "map" :ns "cider.nrepl.middleware.info-test"})))
      (is (match? {:see-also matchers/absent} (session/message {:op "info" :sym "xyz" :ns "cider.nrepl.middleware.info-test"})))
      (is (match? {:see-also matchers/absent} (session/message {:op "info" :sym "xyz"})))))

  (testing "eldoc op"
    (testing "clojure function"
      (is (match? {:status #{"done"}
                   :eldoc  [[] ["x"] ["x" "y"] ["x" "y" "&" "more"]]
                   :name   "+"
                   :type   "function"
                   :class  matchers/absent
                   :ns     "clojure.core"}
                  (session/message {:op "eldoc" :sym "+" :ns "user"}))))

    (testing "clojure special form"
      (is (match? {:status #{"done"}
                   :eldoc  [["try" "expr*" "catch-clause*" "finally-clause?"]]
                   :type   "special-form"}
                  (session/message {:op "eldoc" :sym "try" :ns "user"}))))

    (testing "clojure dot operator"
      (is (match? {:status #{"done"}
                   :type   "special-form"}
                  (session/message {:op "eldoc" :sym "." :ns "user"}))))

    (testing "clojure variable"
      (is (match? {:status    #{"done"}
                   :docstring "This is a test var used to check eldoc returned for a variable."
                   :eldoc     matchers/absent
                   :name      "some-test-var"
                   :type      "variable"
                   :ns        "cider.test-ns.first-test-ns"}
                  (session/message {:op "eldoc" :sym "some-test-var" :ns "cider.test-ns.first-test-ns"}))))

    (testing "java interop method with multiple classes"
      (let [msg {:op "eldoc" :sym ".length" :ns "cider.nrepl.middleware.info-test"}]

        (testing "Without a `:context` being provided"
          (is (match? {:class  (matchers/embeds ["java.lang.String" "java.lang.StringBuffer" "java.lang.CharSequence" "java.lang.StringBuilder"])
                       :member "length"
                       :type   "function"
                       :ns     matchers/absent}
                      (session/message msg)))

          (is (match? {:class  ["java.lang.String"]
                       :member "length"
                       :type   "function"
                       :ns     matchers/absent}
                      (session/message (assoc msg :context "(let [v \"\"] \n (__prefix__ v))")))))))

    (when jdk-sources-present?
      (testing "Fragments for java interop method with single class"
        (let [{:keys [doc-fragments doc-first-sentence-fragments doc-block-tags-fragments]
               :as response}
              (session/message {:op "eldoc" :member "sleep" :class "java.lang.Thread"})]
          (testing (pr-str response)

            (doseq [f [doc-fragments doc-first-sentence-fragments doc-block-tags-fragments]]
              (is (vector? f))
              (is (map? (first f))))))))

    (testing "java interop method with single class"
      (is (match? {:class  ["java.lang.String"]
                   :member "startsWith"
                   :type   "function"
                   :ns     matchers/absent}
                  (session/message {:op "eldoc" :sym ".startsWith" :ns "cider.nrepl.middleware.info-test"}))))

    (when jdk-sources-present?
      (testing "java method eldoc lookup, internal testing methods"
        (is (match? {:eldoc (matchers/embeds [["this"]
                                              ["this" "prim" "things" "generic"]])
                     :type   "function"}
                    (session/message {:op "eldoc" :sym ".fnWithSameName" :ns "cider.nrepl.middleware.info-test"})))))))

(deftest missing-info-test
  (testing "ensure info returns a no-info packet if symbol not found"
    (is (match? {:status #{"no-info" "done"}}
                (session/message {:op "info" :sym "awoeijfxcvb" :ns "user"}))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is unqualified and resolves to a clojure.core var"
    (is (match? {:status #{"done"}}
                (session/message {:op "info" :sym "+" :ns "fakefakefake"}))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is qualified and resolves to a clojure.core var"
    (is (match? {:status #{"done"}}
                (session/message {:op "info" :sym "clojure.core/+" :ns "fakefakefake"}))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is qualified and resolves to a clojure.string var"
    (is (match? {:status #{"done"}}
                (session/message {:op "info" :sym "clojure.string/replace" :ns "fakefakefake"}))))

  (testing "ensure info returns a no-info packet if class not found"
    (is (match? {:status #{"no-info" "done"}}
                (session/message {:op "info" :class "awoeijfxcvb" :member "toString"}))))

  (testing "ensure info returns a no-info packet if member not found"
    (is (match? {:status #{"no-info" "done"}}
                (session/message {:op "info" :class "java.lang.Exception" :member "fakefakefake"})))))

(deftest missing-eldoc-test
  (testing "ensure eldoc returns a no-eldoc packet if symbol not found"
    (is (match? {:status #{"no-eldoc" "done"}}
                (session/message {:op "eldoc" :sym "awoeijfxcvb" :ns "user"}))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is unqualified and resolves to a clojure.core var"
    (is (match? {:status #{"done"}}
                (session/message {:op "eldoc" :sym "+" :ns "fakefakefake"}))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is qualified and resolves to a clojure.core var"
    (is (match? {:status #{"done"}}
                (session/message {:op "eldoc" :sym "clojure.core/+" :ns "fakefakefake"}))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is qualified and resolves to a clojure.string var"
    (is (match? {:status #{"done"}}
                (session/message {:op "eldoc" :sym "clojure.string/replace" :ns "fakefakefake"}))))

  (testing "ensure eldoc returns a no-eldoc packet if class not found"
    (is (match? {:status #{"no-eldoc" "done"}}
                (session/message {:op "eldoc" :class "awoeijfxcvb" :member "toString"}))))

  (testing "ensure eldoc returns a no-eldoc packet if member not found"
    (is (match? {:status #{"no-eldoc" "done"}}
                (session/message {:op "eldoc" :class "java.lang.Exception" :member "fakefakefake"})))))

(deftest error-handling-test
  (is (match? {:status #{"no-info" "done"}} (session/message {:op "info" :class "test"})))
  (is (match? {:status #{"no-eldoc" "done"}} (session/message {:op "eldoc" :class "test"})))
  (is (match? {:status #{"no-info" "done"}} (session/message {:op "info" :member "test"})))
  (is (match? {:status #{"no-eldoc" "done"}} (session/message {:op "eldoc" :member "test"}))))

;;;; eldoc datomic query
(def testing-datomic-query '[:find ?x
                             :in $ ?name
                             :where
                             [?x :person/name ?name]])

(deftest eldoc-datomic-query-test
  (testing "eldoc of inline datomic query"
    (is (match? {:inputs '(["$" "%" "?person-id"])}
                (session/message {:op "eldoc-datomic-query"
                                  :sym "'[:find ?x :in $ % ?person-id]"
                                  :ns "user"}))))

  (testing "eldoc of inline datomic query as map"
    (is (match? {:inputs '(["$" "%" "?person-id"])}
                (session/message {:op "eldoc-datomic-query"
                                  :sym "'{:find [?x] :in [$ % ?person-id]}"
                                  :ns "user"}))))

  (testing "eldoc of datomic query defined as symbol"
    (is (match? {:inputs '(["$" "?name"])}
                (session/message {:op "eldoc-datomic-query"
                                  :sym "testing-datomic-query"
                                  :ns "cider.nrepl.middleware.info-test"}))))

  (testing "eldoc of inline datomic query without :in"
    (is (match? {:inputs '(["$"])}
                (session/message {:op "eldoc-datomic-query"
                                  :sym "'[:find ?x]"
                                  :ns "user"}))))

  (testing "eldoc of inline datomic query as map without :in"
    (is (match? {:inputs '(["$"])}
                (session/message {:op "eldoc-datomic-query"
                                  :sym "'{:find ?x}"
                                  :ns "user"}))))

  (testing "eldoc of empty datomic query"
    (is (match? {:status #{"no-eldoc" "done"}}
                (session/message {:op "eldoc-datomic-query"
                                  :sym ""
                                  :ns "user"})))))
