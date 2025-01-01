(ns cider.nrepl.middleware.info-test
  (:require
   [cider.nrepl.middleware.info :as info]
   [cider.nrepl.test-session :as session]
   [cider.test-ns.first-test-ns :as test-ns]
   [clojure.data]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [orchard.java]
   [orchard.misc :as misc])
  (:import
   (cider.nrepl.test AnotherTestClass TestClass YetAnotherTest)
   (org.apache.commons.lang3 SystemUtils)))

(defprotocol FormatResponseTest
  (proto-foo [this])
  (proto-bar [this] "baz"))

(def jdk-sources-present?
  "Is Java sources files available on the classpath?"
  (boolean (or (io/resource "java/lang/Thread.java")
               (io/resource "java.base/java/lang/Thread.java"))))

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
  ;; protocol docstring
  (is (-> (info/format-response (info/info {:ns "cider.nrepl.middleware.info-test" :sym "proto-foo"}))
          (contains? "doc")
          not))
  (is (-> (info/format-response (info/info {:ns "cider.nrepl.middleware.info-test" :sym "proto-bar"}))
          (get "doc")
          (= "baz"))))

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
    (let [base {:ns (str *ns*)
                :symbol ".codePointCount"}
          base-with-context (assoc base :context "(let [v \"\"] \n (__prefix__ v))")
          response-without-context (info/info base)
          response-with-context (info/info base-with-context)]
      (is (= '[java.lang.String java.lang.StringBuffer java.lang.Character java.lang.StringBuilder]
             (-> response-without-context :candidates keys)))
      (is (not (:candidates response-with-context)))
      (is (= `String
             (:class response-with-context)))
      (is (= {:added "1.0", :ns 'clojure.core, :name '.., :file "clojure/core.clj"}
             (-> base-with-context (assoc :symbol "..") info/info (select-keys [:class :added :ns :name :file])))
          "The context is ignored for the special form `..`"))))

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
      (let [response (session/message {:op "info" :sym "testing-function" :ns "cider.nrepl.middleware.info-test"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "cider.nrepl.middleware.info-test" (:ns response)))
        (is (= "testing-function" (:name response)))
        (is (= "[a b c]" (:arglists-str response)))
        (is (nil? (:macro response)))
        (is (= "This is used for testing" (:doc response)))
        (is (nil? (:spec response)))))

    (testing "get info of a clojure macro"
      (let [response (session/message {:op "info" :sym "testing-macro" :ns "cider.nrepl.middleware.info-test"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "cider.nrepl.middleware.info-test" (:ns response)))
        (is (= "testing-macro" (:name response)))
        (is (= "[pred a b]" (:arglists-str response)))
        (is (= "true" (:macro response)))
        (is (= "a macro for testing" (:doc response)))
        (is (nil? (:spec response)))))

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
      (let [response (session/message {:op "info"
                                       :class "cider.nrepl.test.TestClass"
                                       :member "getInt"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "cider.nrepl.test.TestClass" (:class response)))
        (is (= "getInt" (:member response)))
        (is (= "[this]" (:arglists-str response)))
        (is (= [] (:argtypes response)))
        (is (= "int" (:returns response)))
        (is (= "#{:public}" (:modifiers response)))
        (is (-> response ^String (:javadoc) (.startsWith "cider/nrepl/test/TestClass.html#getInt"))))

      (if (SystemUtils/IS_JAVA_1_8)
        (testing "JDK 1.8 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "cider.nrepl.test.TestClass"
                                           :member "getInt"})]
            (is (= "cider/nrepl/test/TestClass.html#getInt--" (:javadoc response))
                (pr-str response)))))

      (if (SystemUtils/IS_JAVA_9)
        (testing "JDK 9 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "cider.nrepl.test.TestClass"
                                           :member "getInt"})]
            (is (= "cider/nrepl/test/TestClass.html#getInt--" (:javadoc response))
                (pr-str response))))))

    (testing "get info of a private java class method, void return"
      (let [response (session/message {:op "info"
                                       :class "cider.nrepl.test.TestClass"
                                       :member "doSomething"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "cider.nrepl.test.TestClass" (:class response)))
        (is (= "doSomething" (:member response)))
        (when (SystemUtils/IS_JAVA_9)
          ;; Java source parsing is only supported in JDK11+.
          (is (= "[a b c]" (:arglists-str response))))
        (is (= ["int" "int" "java.lang.String"]
               (:argtypes response)))
        (is (= "void" (:returns response)))
        (is (= "#{:private :static}" (:modifiers response)))
        (is (-> response ^String (:javadoc) (.startsWith "cider/nrepl/test/TestClass.html#doSomething"))))

      (if (SystemUtils/IS_JAVA_1_8)
        (testing "JDK 1.8 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "cider.nrepl.test.TestClass"
                                           :member "doSomething"})]
            (is (= "cider/nrepl/test/TestClass.html#doSomething-int-int-java.lang.String-"
                   (:javadoc response))
                (pr-str response)))))

      (if (SystemUtils/IS_JAVA_9)
        (testing "JDK 9 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "cider.nrepl.test.TestClass"
                                           :member "doSomething"})]
            (is (= "cider/nrepl/test/TestClass.html#doSomething-int-int-java.lang.String-"
                   (:javadoc response))
                (pr-str response))))))

    (testing "get info of a java method"
      (let [response (session/message {:op "info"
                                       :class "java.lang.StringBuilder"
                                       :member "capacity"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "java.lang.StringBuilder" (:class response)))
        (is (= "capacity" (:member response)))
        (is (= "[this]" (:arglists-str response)))
        (is (= [] (:argtypes response)))
        (is (= "int" (:returns response)))
        (is (contains? #{"#{:public :bridge :synthetic}"
                         "#{:public}"}
                       (:modifiers response)))
        (is (-> response ^String (:javadoc) (.startsWith "https://docs.oracle.com/"))))

      (if (SystemUtils/IS_JAVA_1_8)
        (testing "JDK 1.8 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "java.lang.StringBuilder"
                                           :member "capacity"})]
            (is (= "https://docs.oracle.com/javase/8/docs/api/java/lang/StringBuilder.html#capacity--"
                   (:javadoc response))
                (pr-str response)))))

      (if (SystemUtils/IS_JAVA_9)
        (testing "JDK 9 Javadoc URL style"
          (let [response (session/message {:op     "info"
                                           :class  "java.lang.StringBuilder"
                                           :member "capacity"})]
            (is (= "https://docs.oracle.com/javase/9/docs/api/java/lang/StringBuilder.html#capacity--"
                   (:javadoc response))
                (pr-str response))))))

    (testing "get info on the dot-operator"
      (let [response (session/message {:op "info" :sym "." :ns "user"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "." (:name response)))
        (is (= "https://clojure.org/java_interop#dot" (:url response)))
        (is (= "true" (:special-form response)))
        (is (-> response ^String (:doc) (.startsWith "The instance member form works")))
        (is (-> response ^String (:forms-str) (.startsWith "(.instanceMember instance args*)\n(.instanceMember")))))

    (testing "get info of a clojure non-core file, located in a jar"
      (let [response (session/message {:op "info" :sym "resource" :ns "clojure.java.io"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "resource" (:name response)))
        (is (= "clojure/java/io.clj" (:resource response)))
        (is (= "clojure.java.io" (:ns response)))
        (is (= "[n]\n[n loader]" (:arglists-str response)))
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

        (is (= [] (:throws individual)))
        (is (= "toString" (:member individual)))
        (is (= "#{:public}" (:modifiers individual)))))

    (testing "Boot support"
      (let [response (session/message {:op "info" :sym "as->" :ns "user"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "clojure.core" (:ns response)))
        (is (= "as->" (:name response)))
        (is (= "[expr name & forms]" (:arglists-str response)))
        (is (= "true" (:macro response)))
        (is (-> response ^String (:doc) (.startsWith "Binds name to expr, evaluates")))))

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
        (is (= #{"done"} status)
            (pr-str reply))
        (is (= "junk-protocol-client" client-name))
        (is (.endsWith file "clojure/data.clj"))
        (is (= "#'clojure.data/Diff" protocol))
        (is (= "cider.nrepl.middleware.info-test" ns))))

    (testing "see also"
      (let [response (session/message {:op "info" :sym "map" :ns "cider.nrepl.middleware.info-test"})]
        (is (= ["clojure.core/map-indexed" "clojure.core/pmap" "clojure.core/amap" "clojure.core/mapcat" "clojure.core/keep" "clojure.core/juxt" "clojure.core/mapv" "clojure.core/reduce" "clojure.core/run!"]
               (:see-also response))
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
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= [[] ["x"] ["x" "y"] ["x" "y" "&" "more"]]
               (:eldoc response)))
        (is (= "clojure.core" (:ns response)))
        (is (not (contains? response :class)))
        (is (= "+" (:name response)))
        (is (= "function" (:type response)))))

    (testing "clojure special form"
      (let [response (session/message {:op "eldoc" :sym "try" :ns "user"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= [["try" "expr*" "catch-clause*" "finally-clause?"]]
               (:eldoc response)))
        (is (= "special-form"
               (:type response)))))

    (testing "clojure dot operator"
      (let [response (session/message {:op "eldoc" :sym "." :ns "user"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "special-form" (:type response)))))

    (testing "clojure variable"
      (let [response (session/message {:op "eldoc" :sym "some-test-var" :ns "cider.test-ns.first-test-ns"})]
        (is (= #{"done"} (:status response))
            (pr-str response))
        (is (= "This is a test var used to check eldoc returned for a variable."
               (:docstring response)))
        (is (= "some-test-var"
               (:name response)))
        (is (= "cider.test-ns.first-test-ns"
               (:ns response)))
        (is (nil? (:eldoc response)))
        (is (= "variable"
               (:type response)))))

    (testing "java interop method with multiple classes"
      (let [msg {:op "eldoc" :sym ".length" :ns "cider.nrepl.middleware.info-test"}]

        (testing "Without a `:context` being provided"
          (let [response (session/message msg)]
            (testing (pr-str response)
              (is (every? (set (:class response)) ;; is a superset of:
                          ["java.lang.String" "java.lang.StringBuffer" "java.lang.CharSequence" "java.lang.StringBuilder"]))
              (is (= "length" (:member response)))
              (is (not (contains? response :ns)))
              (is (= "function" (:type response))))))

        (testing "With a `:context` being provided"
          (let [response (session/message (assoc msg :context "(let [v \"\"] \n (__prefix__ v))"))]
            (testing (pr-str response)
              (is (= ["java.lang.String"]
                     (:class response))))
            (is (= "length" (:member response)))
            (is (not (contains? response :ns)))
            (is (= "function" (:type response)))))))

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
      (let [response (session/message {:op "eldoc" :sym ".startsWith" :ns "cider.nrepl.middleware.info-test"})]
        (is (= ["java.lang.String"]
               (:class response))
            (pr-str response))
        (is (= "startsWith" (:member response)))
        (is (not (contains? response :ns)))
        (is (= "function" (:type response)))))

    (when (SystemUtils/IS_JAVA_9)
      ;; Java source parsing is only supported in JDK11+.
      (testing "java method eldoc lookup, internal testing methods"
        (let [response (session/message {:op "eldoc" :sym "fnWithSameName" :ns "cider.nrepl.middleware.info-test"})]
          (is (= #{["this"]
                   ["a" "b" "c"]
                   ["this" "prim" "things" "generic"]}
                 (set (:eldoc response)))
              (pr-str response))
          (is (= "function" (:type response))))))))

(deftest missing-info-test
  (testing "ensure info returns a no-info packet if symbol not found"
    (let [response (session/message {:op "info" :sym "awoeijfxcvb" :ns "user"})]
      (is (= #{"no-info" "done"} (:status response))
          (pr-str response))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is unqualified and resolves to a clojure.core var"
    (let [response (session/message {:op "info" :sym "+" :ns "fakefakefake"})]
      (is (= #{"done"} (:status response))
          (pr-str response))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is qualified and resolves to a clojure.core var"
    (let [response (session/message {:op "info" :sym "clojure.core/+" :ns "fakefakefake"})]
      (is (= #{"done"} (:status response))
          (pr-str response))))

  (testing "info does not return a no-info packet if ns not found,
but `:sym` is qualified and resolves to a clojure.string var"
    (let [response (session/message {:op "info" :sym "clojure.string/replace" :ns "fakefakefake"})]
      (is (= #{"done"} (:status response))
          (pr-str response))))

  (testing "ensure info returns a no-info packet if class not found"
    (let [response (session/message {:op "info" :class "awoeijfxcvb" :member "toString"})]
      (is (= #{"no-info" "done"} (:status response))
          (pr-str response))))

  (testing "ensure info returns a no-info packet if member not found"
    (let [response (session/message {:op "info" :class "java.lang.Exception" :member "fakefakefake"})]
      (is (= #{"no-info" "done"} (:status response))
          (pr-str response)))))

(deftest missing-eldoc-test
  (testing "ensure eldoc returns a no-eldoc packet if symbol not found"
    (let [response (session/message {:op "eldoc" :sym "awoeijfxcvb" :ns "user"})]
      (is (= #{"no-eldoc" "done"} (:status response))
          (pr-str response))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is unqualified and resolves to a clojure.core var"
    (let [response (session/message {:op "eldoc" :sym "+" :ns "fakefakefake"})]
      (is (= #{"done"} (:status response))
          (pr-str response))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is qualified and resolves to a clojure.core var"
    (let [response (session/message {:op "eldoc" :sym "clojure.core/+" :ns "fakefakefake"})]
      (is (= #{"done"} (:status response))
          (pr-str response))))

  (testing "eldoc does not return a no-eldoc packet if ns not found,
but `:sym` is qualified and resolves to a clojure.string var"
    (let [response (session/message {:op "eldoc" :sym "clojure.string/replace" :ns "fakefakefake"})]
      (is (= #{"done"} (:status response))
          (pr-str response))))

  (testing "ensure eldoc returns a no-eldoc packet if class not found"
    (let [response (session/message {:op "eldoc" :class "awoeijfxcvb" :member "toString"})]
      (is (= #{"no-eldoc" "done"} (:status response))
          (pr-str response))))

  (testing "ensure eldoc returns a no-eldoc packet if member not found"
    (let [response (session/message {:op "eldoc" :class "java.lang.Exception" :member "fakefakefake"})]
      (is (= #{"no-eldoc" "done"} (:status response))
          (pr-str response)))))

(deftest error-handling-test
  (is (= #{"no-info" "done"} (:status (session/message {:op "info" :class "test"}))))
  (is (= #{"no-eldoc" "done"} (:status (session/message {:op "eldoc" :class "test"}))))
  (is (= #{"no-info" "done"} (:status (session/message {:op "info" :member "test"}))))
  (is (= #{"no-eldoc" "done"} (:status (session/message {:op "eldoc" :member "test"})))))

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
      (is (= '(["$" "%" "?person-id"]) (:inputs response))
          (pr-str response))))

  (testing "eldoc of inline datomic query as map"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "'{:find [?x] :in [$ % ?person-id]}"
                                     :ns "user"})]
      (is (= '(["$" "%" "?person-id"]) (:inputs response))
          (pr-str response))))

  (testing "eldoc of datomic query defined as symbol"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "testing-datomic-query"
                                     :ns "cider.nrepl.middleware.info-test"})]
      (is (= '(["$" "?name"]) (:inputs response))
          (pr-str response))))

  (testing "eldoc of inline datomic query without :in"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "'[:find ?x]"
                                     :ns "user"})]
      (is (= '(["$"]) (:inputs response))
          (pr-str response))))

  (testing "eldoc of inline datomic query as map without :in"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym "'{:find ?x}"
                                     :ns "user"})]
      (is (= '(["$"]) (:inputs response))
          (pr-str response))))

  (testing "eldoc of empty datomic query"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :sym ""
                                     :ns "user"})]
      (is (= #{"no-eldoc" "done"} (:status response))
          (pr-str response)))))
