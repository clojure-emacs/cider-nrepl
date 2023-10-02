(ns cider.nrepl.middleware.info-test
  (:require
   [cider.nrepl.middleware.info :as info]
   [cider.nrepl.test-session :as session]
   [cider.test-ns.first-test-ns :as test-ns]
   [clojure.data]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [orchard.misc :as misc])
  (:import
   (cider.nrepl.test AnotherTestClass TestClass YetAnotherTest)
   (org.apache.commons.lang3 SystemUtils)))

(defprotocol FormatResponseTest
  (proto-foo [this])
  (proto-bar [this] "baz"))

(def enriched-classpath?
  "Is enrich-classpath (or something equivalent) augmenting the classpath?"
  (boolean (or (io/resource "java/lang/Thread.java")
               (io/resource "java.base/java/lang/Thread.java"))))

;; TODO: use `orchard.java/parser-next-available?` after Orchar's next release
(def parser-next-available?
  (delay ;; avoid the side-effects at compile-time
    (atom ;; make the result mutable - this is helpful in case the detection below wasn't sufficient
     (and (>= misc/java-api-version 9)
          (try
            ;; indicates that the classes are available
            ;; however it does not indicate if necessary `add-opens=...` JVM flag is in place:
            (and
             (Class/forName "com.sun.tools.javac.tree.DCTree$DCBlockTag")
             (Class/forName "com.sun.tools.javac.code.Type$ArrayType")
             (seq (do
                    (require 'orchard.java.parser-next)
                    ((resolve 'orchard.java.parser-next/source-info) `String))))
            (catch Throwable e
              false))))))

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

    (when @@parser-next-available?
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
        (is (#{"[int int java.lang.String]" ;; without enrich-classpath in
               "[a b c]"} ;; with enrich-classpath in
             (:arglists-str response)))
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
        (is (= (:type response) "special-form"))))

    (testing "clojure dot operator"
      (let [response (session/message {:op "eldoc" :sym "." :ns "user"})]
        (is (= (:status response) #{"done"})
            (pr-str response))
        (is (= (:type response) "special-form"))))

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
              (is (= (:class response)
                     ["java.lang.String"])))
            (is (= "length" (:member response)))
            (is (not (contains? response :ns)))
            (is (= "function" (:type response)))))))

    (when @@parser-next-available?
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
        (is (= (:class response) ["java.lang.String"])
            (pr-str response))
        (is (= (:member response) "startsWith"))
        (is (not (contains? response :ns)))
        (is (= (:type response) "function"))))

    (testing "java method eldoc lookup, internal testing methods"
      (let [response (session/message {:op "eldoc" :sym "fnWithSameName" :ns "cider.nrepl.middleware.info-test"})]
        (is (#{;; without enrich-classpath in:
               #{["this"] ;;TestClass
                 ["int" "java.lang.String" "boolean"] ;;AnotherTestClass
                 ["this" "byte[]" "java.lang.Object[]" "java.util.List"]} ;;YetAnotherTest

               ;; with enrich-classpath in:
               #{["this"]
                 ["a" "b" "c"]
                 ["this" "prim" "things" "generic"]}}
             (set (:eldoc response)))
            (pr-str response))
        (is (= "function" (:type response)))))))

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
