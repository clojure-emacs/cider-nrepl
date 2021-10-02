(ns cider.nrepl.middleware.info-test
  (:require
   [clojure.data]
   [clojure.test :refer :all]
   [clojure.string :as str]
   [cider.nrepl.middleware.info :as info]
   [cider.nrepl.test-session :as session]
   [cider.test-ns.first-test-ns :as test-ns])
  (:import
   [cider.nrepl.test TestClass AnotherTestClass YetAnotherTest]
   [org.apache.commons.lang3 SystemUtils]))

(defprotocol FormatResponseTest
  (proto-foo [this])
  (proto-bar [this] "baz"))

(deftest format-response-test
  (is (re-find #"^(https?|file|jar|zip):" ; resolved either locally or online
               (-> (info/info {:class "java.lang.Object" :member "toString"})
                   (info/format-response)
                   (get "javadoc"))))

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
  ;; protorol docstring
  (is (-> (info/format-response (info/info {:ns "cider.nrepl.middleware.info-test" :sym "proto-foo"}))
          (contains? "doc")
          not))
  (is (-> (info/format-response (info/info {:ns "cider.nrepl.middleware.info-test" :sym "proto-bar"}))
          (get "doc")
          (= "baz"))))

(deftest response-test
  (let [v (ns-resolve 'cider.nrepl.middleware.info 'assoc)
        {:keys [arglists column line added static doc]} (meta v)]
    (is (= (dissoc (info/format-response (info/info {:ns "cider.nrepl.middleware.info" :sym "assoc"})) "file" "see-also")
           {"ns" "clojure.core"
            "name" "assoc"
            "arglists-str" (->> (map pr-str arglists)
                                (str/join \newline))
            "column" column
            "added" added
            "static" (str static)
            "doc" doc
            "line" line
            "resource" "clojure/core.clj"}))))

(deftest info-test
  ;; handle zero-lenth input
  (is (nil? (info/info {:ns (ns-name *ns*) :sym ""})))
  (is (nil? (info/info {:ns "" :sym ""})))

  ;; either symbol or (class method) should be passed
  (is (thrown? Exception
               (info/info {:ns "cider.nrepl.middleware.info-test"
                           :class "Thread"})))

  ;; this is a replacement for (is (not (thrown? ..)))
  (is (nil? (info/info {:class "Thread" :member "UncaughtExceptionHandler"}))))

;; Used below in an integration test
(def ^{:protocol #'clojure.data/Diff} junk-protocol-client)

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
      (let [response (session/message {:op "info" :sym "testing-function" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:ns response) "cider.nrepl.middleware.info-test"))
        (is (= (:name response) "testing-function"))
        (is (= (:arglists-str response) "[a b c]"))
        (is (nil? (:macro response)))
        (is (= (:doc response) "This is used for testing"))
        (is (nil? (:spec response)))))

    (testing "get info of a clojure macro"
      (let [response (session/message {:op "info" :sym "testing-macro" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:ns response) "cider.nrepl.middleware.info-test"))
        (is (= (:name response) "testing-macro"))
        (is (= (:arglists-str response) "[pred a b]"))
        (is (= (:macro response) "true"))
        (is (= (:doc response) "a macro for testing"))
        (is (nil? (:spec response)))))

    (testing "get info of a java instance method with return value"
      (let [response (session/message {:op "info"
                                       :class "cider.nrepl.test.TestClass"
                                       :member "getInt"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:class response) "cider.nrepl.test.TestClass"))
        (is (= (:member response) "getInt"))
        (is (= (:arglists-str response) "[this]"))
        (is (= (:argtypes response) []))
        (is (= (:returns response) "int"))
        (is (= (:modifiers response) "#{:public}"))
        (is (-> response ^String (:javadoc) (.startsWith "cider/nrepl/test/TestClass.html#getInt"))))

      (if (SystemUtils/IS_JAVA_1_8)
        (testing "JDK 1.8 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "cider.nrepl.test.TestClass"
                                           :member "getInt"})]
            (is (= (:javadoc response) "cider/nrepl/test/TestClass.html#getInt--")
                (pr-str response)))))

      (if (SystemUtils/IS_JAVA_9)
        (testing "JDK 9 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "cider.nrepl.test.TestClass"
                                           :member "getInt"})]
            (is (= (:javadoc response) "cider/nrepl/test/TestClass.html#getInt--")
                (pr-str response))))))

    (testing "get info of a private java class method, void return"
      (let [response (session/message {:op "info"
                                       :class "cider.nrepl.test.TestClass"
                                       :member "doSomething"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:class response) "cider.nrepl.test.TestClass"))
        (is (= (:member response) "doSomething"))
        (is (= (:arglists-str response) "[int int java.lang.String]"))
        (is (= (:argtypes response) ["int" "int" "java.lang.String"]))
        (is (= (:returns response) "void"))
        (is (= (:modifiers response) "#{:private :static}"))
        (is (-> response ^String (:javadoc) (.startsWith "cider/nrepl/test/TestClass.html#doSomething"))))

      (if (SystemUtils/IS_JAVA_1_8)
        (testing "JDK 1.8 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "cider.nrepl.test.TestClass"
                                           :member "doSomething"})]
            (is (= (:javadoc response) "cider/nrepl/test/TestClass.html#doSomething-int-int-java.lang.String-")
                (pr-str response)))))

      (if (SystemUtils/IS_JAVA_9)
        (testing "JDK 9 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "cider.nrepl.test.TestClass"
                                           :member "doSomething"})]
            (is (= (:javadoc response) "cider/nrepl/test/TestClass.html#doSomething-int-int-java.lang.String-")
                (pr-str response))))))

    (testing "get info of a java method"
      (let [response (session/message {:op "info"
                                       :class "java.lang.StringBuilder"
                                       :member "capacity"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:class response) "java.lang.StringBuilder"))
        (is (= (:member response) "capacity"))
        (is (= (:arglists-str response) "[this]"))
        (is (= (:argtypes response) []))
        (is (= (:returns response) "int"))
        (is (= (:modifiers response) "#{:public :bridge :synthetic}"))
        (is (-> response ^String (:javadoc) (.startsWith "https://docs.oracle.com/"))))

      (if (SystemUtils/IS_JAVA_1_8)
        (testing "JDK 1.8 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "java.lang.StringBuilder"
                                           :member "capacity"})]
            (is (= (:javadoc response) "https://docs.oracle.com/javase/8/docs/api/java/lang/StringBuilder.html#capacity--")
                (pr-str response)))))

      (if (SystemUtils/IS_JAVA_9)
        (testing "JDK 9 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "java.lang.StringBuilder"
                                           :member "capacity"})]
            (is (= (:javadoc response) "https://docs.oracle.com/javase/9/docs/api/java/lang/StringBuilder.html#capacity--")
                (pr-str response))))))

    (testing "get info on the dot-operator"
      (let [response (session/message {:op "info" :sym "." :ns "user"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:name response) "."))
        (is (= (:url response) "https://clojure.org/java_interop#dot"))
        (is (= (:special-form response) "true"))
        (is (-> response ^String (:doc) (.startsWith "The instance member form works")))
        (is (-> response ^String (:forms-str) (.startsWith "(.instanceMember instance args*)\n(.instanceMember")))))

    (testing "get info of a clojure non-core file, located in a jar"
      (let [response (session/message {:op "info" :sym "resource" :ns "clojure.java.io"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:name response) "resource"))
        (is (= (:resource response) "clojure/java/io.clj"))
        (is (= (:ns response) "clojure.java.io"))
        (is (= (:arglists-str response) "[n]\n[n loader]"))
        (is (-> response ^String (:doc) (.startsWith "Returns the URL for a named")))
        (is (-> response ^String (:file) (.startsWith "jar:file:")))))

    (testing "nested members"
      (let [response   (session/message {:op "info" :ns (ns-name *ns*) :sym "toString"})
            candidates (:candidates response)
            individual (:java.lang.Exception candidates)]
        (is (contains? candidates :java.lang.NoSuchFieldException)
            (pr-str response))
        (is (contains? candidates :java.lang.Package))
        (is (contains? candidates :java.lang.LinkageError))

        (is (= (:throws individual) []))
        (is (= (:member individual) "toString"))
        (is (= (:modifiers individual) "#{:public}"))))

    (testing "Boot support"
      (try
        (System/setProperty "fake.class.path" (System/getProperty "java.class.path"))
        (let [response (session/message {:op "info" :sym "as->" :ns "user"})]
          (is (= (:status response) #{"done"})
              (pr-str response))
          (is (= (:ns response) "clojure.core"))
          (is (= (:name response) "as->"))
          (is (= (:arglists-str response) "[expr name & forms]"))
          (is (= (:macro response) "true"))
          (is (-> response ^String (:doc) (.startsWith "Binds name to expr, evaluates"))))
        (finally
          (System/clearProperty "fake.class.path"))))

    (testing "get protocol info"
      (let [reply       (session/message {:op "info"
                                          :ns "cider.nrepl.middleware.info-test"
                                          :sym "junk-protocol-client"})
            status      (:status reply)
            client-name (:name reply)
            ^String
            file        (:file reply)
            protocol    (:protocol reply)
            ns          (:ns reply)]
        (is (= status #{"done"})
            (pr-str reply))
        (is (= client-name "junk-protocol-client"))
        (is (.endsWith file "clojure/data.clj"))
        (is (= protocol "#'clojure.data/Diff"))
        (is (= ns "cider.nrepl.middleware.info-test"))))

    (testing "see also"
      (let [response (session/message {:op "info" :sym "map" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:see-also response)
               ["clojure.core/map-indexed" "clojure.core/pmap" "clojure.core/amap" "clojure.core/mapcat" "clojure.core/keep" "clojure.core/juxt" "clojure.core/mapv" "clojure.core/reduce" "clojure.core/run!"])
            (pr-str response)))
      (let [response (session/message {:op "info" :sym "xyz" :ns "cider.nrepl.middleware.info-test"})]
        (is (nil? (:see-also response))
            (pr-str response)))

      (let [response (session/message {:op "info" :sym "xyz"})]
        (is (nil? (:see-also response))
            (pr-str response)))))

  (testing "eldoc op"
    (testing "clojure function"
      (let [response (session/message {:op "eldoc" :sym "+" :ns "user"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:eldoc response) [[] ["x"] ["x" "y"] ["x" "y" "&" "more"]]))
        (is (= (:ns response) "clojure.core"))
        (is (not (contains? response :class)))
        (is (= (:name response) "+"))
        (is (= (:type response) "function"))))

    (testing "clojure special form"
      (let [response (session/message {:op "eldoc" :sym "try" :ns "user"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:eldoc response) [["try" "expr*" "catch-clause*" "finally-clause?"]]))
        (is (= (:type response) "function"))))

    (testing "clojure dot operator"
      (let [response (session/message {:op "eldoc" :sym "." :ns "user"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:type response) "function"))))

    (testing "clojure variable"
      (let [response (session/message {:op "eldoc" :sym "some-test-var" :ns "cider.test-ns.first-test-ns"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:docstring response) "This is a test var used to check eldoc returned for a variable."))
        (is (= (:name response) "some-test-var"))
        (is (= (:ns response) "cider.test-ns.first-test-ns"))
        (is (nil? (:eldoc response)))
        (is (= (:type response) "variable"))))

    (testing "java interop method with multiple classes"
      (let [response (session/message {:op "eldoc" :sym ".length" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:class response)
               ["java.lang.String" "java.lang.StringBuffer" "java.lang.CharSequence" "java.lang.StringBuilder"])
            (pr-str response))
        (is (= (:member response) "length"))
        (is (not (contains? response :ns)))
        (is (= (:type response) "function"))))

    (testing "java interop method with single class"
      (let [response (session/message {:op "eldoc" :sym ".startsWith" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:class response) ["java.lang.String"])
            (pr-str response))
        (is (= (:member response) "startsWith"))
        (is (not (contains? response :ns)))
        (is (= (:type response) "function"))))

    (testing "java method eldoc lookup, internal testing methods"
      (let [response (session/message {:op "eldoc" :sym "fnWithSameName" :ns "cider.nrepl.middleware.info-test"})]
        (is (= #{["this"] ;;TestClass
                 ["int" "java.lang.String" "boolean"] ;;AnotherTestClass
                 ["this" "byte[]" "java.lang.Object[]" "java.util.List"]} ;;YetAnotherTest
               (set (:eldoc response)))
            (pr-str response))
        (is (= (:type response) "function"))))))

(deftest missing-info-test
  (testing "ensure info returns a no-info packet if symbol not found"
    (let [response (session/message {:op "info" :sym "awoeijfxcvb" :ns "user"})]
      (is (= (:status response) #{"no-info" "done"})
          (pr-str response))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is unqualified and resolves to a clojure.core var"
    (let [response (session/message {:op "info" :sym "+" :ns "fakefakefake"})]
      (is (= (:status response) #{"done"})
          (pr-str response))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is qualified and resolves to a clojure.core var"
    (let [response (session/message {:op "info" :sym "clojure.core/+" :ns "fakefakefake"})]
      (is (= (:status response) #{"done"})
          (pr-str response))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is qualified and resolves to a clojure.string var"
    (let [response (session/message {:op "info" :sym "clojure.string/replace" :ns "fakefakefake"})]
      (is (= (:status response) #{"done"})
          (pr-str response))))

  (testing "ensure info returns a no-info packet if class not found"
    (let [response (session/message {:op "info" :class "awoeijfxcvb" :member "toString"})]
      (is (= (:status response) #{"no-info" "done"})
          (pr-str response))))

  (testing "ensure info returns a no-info packet if member not found"
    (let [response (session/message {:op "info" :class "java.lang.Exception" :member "fakefakefake"})]
      (is (= (:status response) #{"no-info" "done"})
          (pr-str response)))))

(deftest missing-eldoc-test
  (testing "ensure eldoc returns a no-eldoc packet if symbol not found"
    (let [response (session/message {:op "eldoc" :sym "awoeijfxcvb" :ns "user"})]
      (is (= (:status response) #{"no-eldoc" "done"})
          (pr-str response))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is unqualified and resolves to a clojure.core var"
    (let [response (session/message {:op "eldoc" :sym "+" :ns "fakefakefake"})]
      (is (= (:status response) #{"done"})
          (pr-str response))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is qualified and resolves to a clojure.core var"
    (let [response (session/message {:op "eldoc" :sym "clojure.core/+" :ns "fakefakefake"})]
      (is (= (:status response) #{"done"})
          (pr-str response))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is qualified and resolves to a clojure.string var"
    (let [response (session/message {:op "eldoc" :sym "clojure.string/replace" :ns "fakefakefake"})]
      (is (= (:status response) #{"done"})
          (pr-str response))))

  (testing "ensure eldoc returns a no-eldoc packet if class not found"
    (let [response (session/message {:op "eldoc" :class "awoeijfxcvb" :member "toString"})]
      (is (= (:status response) #{"no-eldoc" "done"})
          (pr-str response))))

  (testing "ensure eldoc returns a no-eldoc packet if member not found"
    (let [response (session/message {:op "eldoc" :class "java.lang.Exception" :member "fakefakefake"})]
      (is (= (:status response) #{"no-eldoc" "done"})
          (pr-str response)))))

(deftest error-handling-test
  (testing "handle the exception thrown if no member provided to a java class info query"
    (let [response (session/message {:op "info" :class "test"})]
      (is (= (:status response) #{"info-error" "done"})
          (pr-str response))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (-> response ^String (:err) (.startsWith "java.lang.Exception: Either")))
      (is (:pp-stacktrace response))))

  (testing "handle the exception thrown if no member provided to a java class eldoc query"
    (let [response (session/message {:op "eldoc" :class "test"})]
      (is (= (:status response) #{"eldoc-error" "done"})
          (pr-str response))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (-> response ^String (:err) (.startsWith "java.lang.Exception: Either")))
      (is (:pp-stacktrace response))))

  (testing "handle the exception thrown if no class provided to a java member info query"
    (let [response (session/message {:op "info" :member "test"})]
      (is (= (:status response) #{"info-error" "done"})
          (pr-str response))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (-> response ^String (:err) (.startsWith "java.lang.Exception: Either")))
      (is (:pp-stacktrace response))))

  (testing "handle the exception thrown if no class provided to a java member eldoc query"
    (let [response (session/message {:op "eldoc" :member "test"})]
      (is (= (:status response) #{"eldoc-error" "done"})
          (pr-str response))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (-> response ^String (:err) (.startsWith "java.lang.Exception: Either")))
      (is (:pp-stacktrace response))))

  (testing "handle the exception thrown if there's a mocked info retrieval error"
    (with-redefs [info/info (fn [& _] (throw (Exception. "info-exception")))]
      (let [response (session/message {:op "info" :sym "test" :ns "user"})]
        (is (= (:status response) #{"info-error" "done"})
            (pr-str response))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: info-exception")))
        (is (:pp-stacktrace response)))))

  (testing "handle the exception thrown if there's a mocked eldoc retreival error "
    (with-redefs [info/eldoc-reply (fn [& _] (throw (Exception. "eldoc-exception")))]
      (let [response (session/message {:op "eldoc" :sym "test" :ns "user"})]
        (is (= (:status response) #{"eldoc-error" "done"})

            (pr-str response))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: eldoc-exception")))
        (is (:pp-stacktrace response))))))

;;;; eldoc datomic query
(def testing-datomic-query '[:find ?x
                             :in $ ?name
                             :where
                             [?x :person/name ?name]])

(deftest eldoc-datomic-query-test
  (testing "eldoc of inline datomic query"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "'[:find ?x :in $ % ?person-id]"
                                     :ns "user"})]
      (is (= (:inputs response) '(["$" "%" "?person-id"]))
          (pr-str response))))

  (testing "eldoc of inline datomic query as map"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "'{:find [?x] :in [$ % ?person-id]}"
                                     :ns "user"})]
      (is (= (:inputs response) '(["$" "%" "?person-id"]))
          (pr-str response))))

  (testing "eldoc of datomic query defined as symbol"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "testing-datomic-query"
                                     :ns "cider.nrepl.middleware.info-test"})]
      (is (= (:inputs response) '(["$" "?name"]))
          (pr-str response))))

  (testing "eldoc of inline datomic query without :in"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "'[:find ?x]"
                                     :ns "user"})]
      (is (= (:inputs response) '(["$"]))
          (pr-str response))))

  (testing "eldoc of inline datomic query as map without :in"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "'{:find ?x}"
                                     :ns "user"})]
      (is (= (:inputs response) '(["$"]))
          (pr-str response))))

  (testing "eldoc of empty datomic query"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym ""
                                     :ns "user"})]
      (is (= (:status response) #{"no-eldoc" "done"})
          (pr-str response)))))
