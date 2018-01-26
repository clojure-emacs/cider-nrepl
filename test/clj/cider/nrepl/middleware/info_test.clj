(ns cider.nrepl.middleware.info-test
  (:require [clojure.data]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.repl :as repl]
            [clojure.set :as set]
            [clojure.string :as str]
            [cider.nrepl.middleware.info :as info]
            [orchard.java :as java]
            [orchard.meta :as m]
            [orchard.misc :as util]
            [orchard.classloader :refer [class-loader]]
            [cider.nrepl.test-session :as session]
            [cider.test-ns.first-test-ns :as test-ns])
  (:import [cider.nrepl.test TestClass AnotherTestClass YetAnotherTest]))

(defn file
  [x]
  (:file (info/file-info x)))

(defn relative
  [x]
  (:resource (info/file-info x)))

(deftest javadoc-url-test
  (testing "java 1.7"
    (is (= "http://docs.oracle.com/javase/7/docs/api/java/lang/StringBuilder.html#charAt(int)"
           (with-redefs [util/java-api-version "7"]
             (-> (info/info-java 'java.lang.StringBuilder 'charAt)
                 (info/format-response)
                 (get "javadoc"))))))

  (testing "java 1.8"
    (is (= "http://docs.oracle.com/javase/8/docs/api/java/lang/StringBuilder.html#charAt-int-"
           (with-redefs [util/java-api-version "8"]
             (-> (info/info-java 'java.lang.StringBuilder 'charAt)
                 (info/format-response)
                 (get "javadoc")))))))

(deftest resource-path-test
  (is (= (class (file (subs (str (clojure.java.io/resource "clojure/core.clj")) 4)))
         java.net.URL))
  (is (= (class (file "clojure/core.clj"))
         java.net.URL))
  (is (= (class (file "clojure-1.7.0.jar:clojure/core.clj"))
         java.net.URL))
  (is (= (class (file "test/clj/cider/nrepl/middleware/info_test.clj"))
         java.net.URL))
  (is (relative "clojure/core.clj"))
  (is (nil? (relative "notclojure/core.clj")))
  (is (nil? (info/resource-path "jar:file:fake.jar!/fake/file.clj"))))

(deftest boot-resource-path-test
  (let [tmp-dir-name (System/getProperty "java.io.tmpdir")
        tmp-file-name "boot-test.txt"
        tmp-file-path (str tmp-dir-name (System/getProperty "file.separator") tmp-file-name)]
    (spit tmp-file-path "test")
    (testing "when fake.class.path is not set"
      (is (not (= (class (file tmp-file-name))
                  java.net.URL)))
      (is (= (file tmp-file-name) tmp-file-name)))
    (testing "when fake.class.path is set"
      (try
        (System/setProperty "fake.class.path" tmp-dir-name)
        (is (= (class (file tmp-file-name))
               java.net.URL))
        (is (= (.getPath (file tmp-file-name))
               tmp-file-path))
        (finally
          (System/clearProperty "fake.class.path"))))
    (testing "when classpath is a jar"
      (let [tmp-jar-path "jar:file:fake/clojure.jar"]
        (try
          (System/setProperty "fake.class.path" tmp-jar-path)
          (is (some #{"file:fake/clojure.jar"}
                    (->> (class-loader) .getURLs (map str))))
          (finally
            (System/clearProperty "fake.class.path")))))
    (testing "include sources when avaliable"
      (when-let [src-url (java/jdk-resource-url "src.zip")]
        (try
          (System/setProperty "fake.class.path" tmp-dir-name)
          (is (some #{src-url} (.getURLs (class-loader))))
          (finally
            (System/clearProperty "fake.class.path")))))))

(deftest special-sym-meta-test

  (testing "Names are correct for `&`, `catch`, `finally`"
    (is (= '& (:name (m/special-sym-meta '&))))
    (is (= 'catch (:name (m/special-sym-meta 'catch))))
    (is (= 'finally (:name (m/special-sym-meta 'finally)))))

  (testing "Name is correct for `clojure.core/import*`"
    ;; Only compiler special to be namespaced
    (is (= 'clojure.core/import* (:name (m/special-sym-meta 'clojure.core/import*)))))

  (testing "No ns for &, which uses fn's info"
    (is (nil? (:ns (m/special-sym-meta '&)))))

  (testing "Returns nil for unknown symbol"
    (is (nil? (m/special-sym-meta 'unknown)))))

(deftype T [])

(deftest info-test
  (is (info/info-clj 'cider.nrepl.middleware.info 'io))

  (is (info/info-clj 'cider.nrepl.middleware.info 'info-clj))

  (is (info/info-clj 'cider.nrepl.middleware.info 'java.lang.Class))
  (is (info/info-clj 'cider.nrepl.middleware.info 'Class/forName))
  (is (info/info-clj 'cider.nrepl.middleware.info '.toString))

  (is (not (info/info-clj 'clojure.core (gensym "non-existing"))))
  (is (info/info-clj 'cider.nrepl.middleware.info-test 'T)
      "Check that deftype T (which returns nil for .getPackage), doesn't throw")

  (is (= (the-ns 'clojure.core) (:ns (info/info-clj 'cider.nrepl.middleware.info 'str))))

  ;; special forms are marked as such and nothing else is (for all syms in ns)
  (let [ns 'cider.nrepl.middleware.info
        spec-forms (into '#{letfn fn let loop} (keys @#'repl/special-doc-map))
        infos (->> (into spec-forms (keys (ns-map ns)))
                   (map (partial info/info-clj ns)))]
    (is (= spec-forms (->> (-> (group-by :special-form infos)
                               (get true))
                           (map :name)
                           (set)))))

  (is (info/info-java 'clojure.lang.Atom 'swap))

  (is (re-find #"^(http|file|jar|zip):" ; resolved either locally or online
               (-> (info/info-java 'java.lang.Object 'toString)
                   (info/format-response)
                   (get "javadoc"))))

  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core)))

  (is (-> (info/info-clj 'cider.nrepl.middleware.info 'clojure.core)
          (dissoc :file)
          (info/format-response)))

  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core//)))
  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core/+)))
  ;; used to crash, sym is parsed as a class name
  (is (nil? (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'notincanter.core))))
  ;; unfound nses should fall through
  (is (nil? (info/format-response (info/info-clj 'cider.nrepl.middleware.nonexistent-namespace 'a-var))))

  ;; handle zero-lenth input
  (is (nil? (info/info {:ns (ns-name *ns*) :symbol ""})))
  (is (nil? (info/info {:ns "" :symbol ""})))

  ;; either symbol or (class method) should be passed
  (is (thrown? Exception
               (info/info {:ns "cider.nrepl.middleware.info-test"
                           :class "Thread"})))

  ;; this is a replacement for (is (not (thrown? ..)))
  (is (nil? (info/info {:class "Thread" :member "UncaughtExceptionHandler"}))))

(deftest response-test
  (let [v (ns-resolve 'cider.nrepl.middleware.info 'assoc)
        {:keys [arglists column line added static doc]} (meta v)]
    (is (= (dissoc (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'assoc)) "file")
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

;;;; eldoc
(def test-eldoc-info {:arglists '([x] [x y])})

(def test-eldoc-info-special-form {:forms ['(.instanceMember instance args*)
                                           '(.instanceMember Classname args*)
                                           '(Classname/staticMethod args*)
                                           'Classname/staticField]
                                   :special-form true})

(def test-eldoc-info-candidates
  {:candidates '{X {:arglists ([x])}
                 Y {:arglists ([x] [x y z])}
                 Z {:arglists ([])}}})

(deftest test-extract-arglists
  (is (= (info/extract-arglists test-eldoc-info) '([x] [x y])))
  (is (= (info/extract-arglists test-eldoc-info-candidates)
         '([] [x] [x y z])))
  (is (= (info/extract-arglists test-eldoc-info-special-form)
         '([.instanceMember instance args*]
           [.instanceMember Classname args*]
           [Classname/staticMethod args*]
           [Classname/staticField]))))

(deftest format-arglists-test
  (is (= (info/format-arglists (info/extract-arglists test-eldoc-info)) '(["x"] ["x" "y"])))
  (is (= (info/format-arglists (info/extract-arglists test-eldoc-info-candidates))
         '([] ["x"] ["x" "y" "z"]))))

(deftest test-extract-arglists
  (is (info/extract-arglists (info/info {:ns "clojure.core" :symbol "map"})))
  (is (info/extract-arglists (info/info {:ns "clojure.core" :symbol ".toString"})))
  (is (info/extract-arglists (info/info {:ns "clojure.core" :symbol "."})))
  (is (not (info/extract-arglists (info/info {:ns "clojure.core" :symbol (gensym "non-existing")})))))

(deftest var-meta-test
  ;; Test files can't be found on the class path.
  (is (:file (m/var-meta #'m/var-meta)))
  (is (re-find #"cider-nrepl" (:file (#'m/maybe-add-file {:ns (find-ns 'cider.nrepl.middleware.info)}))))
  (is (not (re-find #"/form-init[^/]*$" (:file (m/var-meta (eval '(do (in-ns 'cider.nrepl.middleware.info) (def pok 10)))))))))

(deftest javadoc-info-unit-test
  (testing "Get an HTTP URL for a Sun/Oracle Javadoc"
    (testing "Javadoc 1.7 format"
      (let [reply      (info/javadoc-info "java/lang/StringBuilder.html#capacity()")
            url        (:javadoc reply)
            exp-suffix "/docs/api/java/lang/StringBuilder.html#capacity()"]
        (is (.endsWith url exp-suffix))))

    (testing "Javadoc 1.8 format"
      (let [reply      (info/javadoc-info "java/lang/StringBuilder.html#capacity--")
            url        (:javadoc reply)
            exp-suffix "/docs/api/java/lang/StringBuilder.html#capacity--"]
        (is (.endsWith url exp-suffix)))))

  (testing "Get general URL for a clojure javadoc"
    (let [reply    (info/javadoc-info "clojure/java/io.clj")
          url      (:javadoc reply)
          url-type (class url)
          exp-type java.net.URL]
      (is (= url-type exp-type))))

  (testing "Get URL for commonly used Java libraries via the *remote-javadocs* mechanism"
    (let [reply    (info/javadoc-info "com/amazonaws/services/lambda/AWSLambdaClient.html#listFunctions()")
          url      (:javadoc reply)]
      (is (= url "http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/lambda/AWSLambdaClient.html#listFunctions()"))))

  (testing "Get fall through URL type for other Javadocs (external libs?)"
    (let [reply (info/javadoc-info "http://some/other/url")
          url (:javadoc reply)]
      (is (= url "http://some/other/url")))))

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
      (let [response (session/message {:op "info" :symbol "testing-function" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:status response) #{"done"}))
        (is (= (:ns response) "cider.nrepl.middleware.info-test"))
        (is (= (:name response) "testing-function"))
        (is (= (:arglists-str response) "[a b c]"))
        (is (nil? (:macro response)))
        (is (= (:doc response) "This is used for testing"))
        (is (nil? (:spec response)))))

    (testing "get info of a clojure macro"
      (let [response (session/message {:op "info" :symbol "testing-macro" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:status response) #{"done"}))
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
        (is (= (:status response) #{"done"}))
        (is (= (:class response) "cider.nrepl.test.TestClass"))
        (is (= (:member response) "getInt"))
        (is (= (:arglists-str response) "[this]"))
        (is (= (:argtypes response) []))
        (is (= (:returns response) "int"))
        (is (= (:modifiers response) "#{:public}"))
        (is (.startsWith (:javadoc response) "cider/nrepl/test/TestClass.html#getInt")))

      (testing "JDK 1.7 Javadoc URL style"
        (with-redefs [util/java-api-version "7"]
          (let [response (session/message {:op "info"
                                           :class "cider.nrepl.test.TestClass"
                                           :member "getInt"})]
            (is (= (:javadoc response) "cider/nrepl/test/TestClass.html#getInt()")))))

      (testing "JDK 1.8 Javadoc URL style"
        (with-redefs [util/java-api-version "8"]
          (let [response (session/message {:op "info"
                                           :class "cider.nrepl.test.TestClass"
                                           :member "getInt"})]
            (is (= (:javadoc response) "cider/nrepl/test/TestClass.html#getInt--"))))))

    (testing "get info of a private java class method, void return"
      (let [response (session/message {:op "info"
                                       :class "cider.nrepl.test.TestClass"
                                       :member "doSomething"})]
        (is (= (:status response) #{"done"}))
        (is (= (:class response) "cider.nrepl.test.TestClass"))
        (is (= (:member response) "doSomething"))
        (is (= (:arglists-str response) "[int int java.lang.String]"))
        (is (= (:argtypes response) ["int" "int" "java.lang.String"]))
        (is (= (:returns response) "void"))
        (is (= (:modifiers response) "#{:private :static}"))
        (is (.startsWith (:javadoc response) "cider/nrepl/test/TestClass.html#doSomething")))

      (testing "JDK 1.7 Javadoc URL style"
        (with-redefs [util/java-api-version "7"]
          (let [response (session/message {:op "info"
                                           :class "cider.nrepl.test.TestClass"
                                           :member "doSomething"})]
            (is (= (:javadoc response) "cider/nrepl/test/TestClass.html#doSomething(int,%20int,%20java.lang.String)")))))

      (testing "JDK 1.8 Javadoc URL style"
        (with-redefs [util/java-api-version "8"]
          (let [response (session/message {:op "info"
                                           :class "cider.nrepl.test.TestClass"
                                           :member "doSomething"})]
            (is (= (:javadoc response) "cider/nrepl/test/TestClass.html#doSomething-int-int-java.lang.String-"))))))

    (testing "get info of a java method"
      (let [response (session/message {:op "info"
                                       :class "java.lang.StringBuilder"
                                       :member "capacity"})]
        (is (= (:status response) #{"done"}))
        (is (= (:class response) "java.lang.StringBuilder"))
        (is (= (:member response) "capacity"))
        (is (= (:arglists-str response) "[this]"))
        (is (= (:argtypes response) []))
        (is (= (:returns response) "int"))
        (is (= (:modifiers response) "#{:public :bridge :synthetic}"))
        (is (.startsWith (:javadoc response) "http://docs.oracle.com/javase")))

      (testing "JDK 1.7 Javadoc URL style"
        (with-redefs [util/java-api-version "7"]
          (let [response (session/message {:op "info"
                                           :class "java.lang.StringBuilder"
                                           :member "capacity"})]
            (is (= (:javadoc response) "http://docs.oracle.com/javase/7/docs/api/java/lang/StringBuilder.html#capacity()")))))

      (testing "JDK 1.8 Javadoc URL style"
        (with-redefs [util/java-api-version "8"]
          (let [response (session/message {:op "info"
                                           :class "java.lang.StringBuilder"
                                           :member "capacity"})]
            (is (= (:javadoc response) "http://docs.oracle.com/javase/8/docs/api/java/lang/StringBuilder.html#capacity--"))))))

    (testing "get info on the dot-operator"
      (let [response (session/message {:op "info" :symbol "." :ns "user"})]
        (is (= (:status response) #{"done"}))
        (is (= (:name response) "."))
        (is (= (:url response) "https://clojure.org/java_interop#dot"))
        (is (= (:special-form response) "true"))
        (is (.startsWith (:doc response) "The instance member form works"))
        (is (.startsWith (:forms-str response) "(.instanceMember instance args*)\n(.instanceMember"))))

    (testing "get info of a clojure non-core file, located in a jar"
      (let [response (session/message {:op "info" :symbol "resource" :ns "clojure.java.io"})]
        (is (= (:status response) #{"done"}))
        (is (= (:name response) "resource"))
        (is (= (:resource response) "clojure/java/io.clj"))
        (is (= (:ns response) "clojure.java.io"))
        (is (= (:arglists-str response) "[n]\n[n loader]"))
        (is (.startsWith (:doc response) "Returns the URL for a named"))
        (is (.startsWith (:file response) "jar:file:"))))

    (testing "nested members"
      (let [response   (session/message {:op "info" :ns (ns-name *ns*) :symbol "toString"})
            candidates (:candidates response)
            individual (:java.lang.Exception candidates)]
        (is (contains? candidates :java.lang.NoSuchFieldException))
        (is (contains? candidates :java.lang.Package))
        (is (contains? candidates :java.lang.LinkageError))

        (is (= (:throws individual) []))
        (is (= (:member individual) "toString"))
        (is (= (:modifiers individual) "#{:public}"))))

    (testing "Boot support"
      (try
        (System/setProperty "fake.class.path" (System/getProperty "java.class.path"))
        (let [response (session/message {:op "info" :symbol "as->" :ns "user"})]
          (is (= (:status response) #{"done"}))
          (is (= (:ns response) "clojure.core"))
          (is (= (:name response) "as->"))
          (is (= (:arglists-str response) "[expr name & forms]"))
          (is (= (:macro response) "true"))
          (is (.startsWith (:doc response) "Binds name to expr, evaluates")))
        (finally
          (System/clearProperty "fake.class.path"))))

    (testing "get protocol info"
      (let [reply       (session/message {:op "info"
                                          :ns "cider.nrepl.middleware.info-test"
                                          :symbol "junk-protocol-client"})
            status      (:status reply)
            client-name (:name reply)
            file        (:file reply)
            protocol    (:protocol reply)
            ns          (:ns reply)]
        (is (= status #{"done"}))
        (is (= client-name "junk-protocol-client"))
        (is (.endsWith file "clojure/data.clj"))
        (is (= protocol "#'clojure.data/Diff"))
        (is (= ns "cider.nrepl.middleware.info-test"))))

    (testing "see also"
      (let [response (session/message {:op "info" :symbol "map" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:see-also response)
               ["clojure.core/map-indexed" "clojure.core/pmap" "clojure.core/amap" "clojure.core/mapcat" "clojure.core/keep" "clojure.core/juxt" "clojure.core/mapv" "clojure.core/reduce" "clojure.core/run!"])))
      (let [response (session/message {:op "info" :symbol "xyz" :ns "cider.nrepl.middleware.info-test"})]
        (is (nil? (:see-also response))))

      (let [response (session/message {:op "info" :symbol "xyz"})]
        (is (nil? (:see-also response))))))

  (testing "eldoc op"
    (testing "clojure function"
      (let [response (session/message {:op "eldoc" :symbol "+" :ns "user"})]
        (is (= (:status response) #{"done"}))
        (is (= (:eldoc response) [[] ["x"] ["x" "y"] ["x" "y" "&" "more"]]))
        (is (= (:ns response) "clojure.core"))
        (is (not (contains? response :class)))
        (is (= (:name response) "+"))
        (is (= (:type response) "function"))))

    (testing "clojure special form"
      (let [response (session/message {:op "eldoc" :symbol "try" :ns "user"})]
        (is (= (:status response) #{"done"}))
        (is (= (:eldoc response) [["try" "expr*" "catch-clause*" "finally-clause?"]]))
        (is (= (:type response) "function"))))

    (testing "clojure dot operator"
      (let [response (session/message {:op "eldoc" :symbol "." :ns "user"})]
        (is (= (:status response) #{"done"}))
        (is (= (:type response) "function"))))

    (testing "clojure variable"
      (let [response (session/message {:op "eldoc" :symbol "some-test-var" :ns "cider.test-ns.first-test-ns"})]
        (is (= (:status response) #{"done"}))
        (is (= (:docstring response) "This is a test var used to check eldoc returned for a variable."))
        (is (= (:name response) "some-test-var"))
        (is (= (:ns response) "cider.test-ns.first-test-ns"))
        (is (nil? (:eldoc response)))
        (is (= (:type response) "variable"))))

    (testing "java interop method with multiple classes"
      (let [response (session/message {:op "eldoc" :symbol ".length" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:class response)
               ["java.lang.String" "java.lang.StringBuffer" "java.lang.CharSequence" "java.lang.StringBuilder"]))
        (is (= (:member response) "length"))
        (is (not (contains? response :ns)))
        (is (= (:type response) "function"))))

    (testing "java interop method with single class"
      (let [response (session/message {:op "eldoc" :symbol ".startsWith" :ns "cider.nrepl.middleware.info-test"})]
        (is (= (:class response) ["java.lang.String"]))
        (is (= (:member response) "startsWith"))
        (is (not (contains? response :ns)))
        (is (= (:type response) "function"))))

    (testing "java method eldoc lookup, internal testing methods"
      (let [response (session/message {:op "eldoc" :symbol "fnWithSameName" :ns "cider.nrepl.middleware.info-test"})]
        (is (= #{["this"] ;;TestClass
                 ["int" "java.lang.String" "boolean"] ;;AnotherTestClass
                 ["this" "byte[]" "java.lang.Object[]" "java.util.List"]} ;;YetAnotherTest
               (set (:eldoc response))))
        (is (= (:type response) "function"))))))

(deftest missing-info-test
  (testing "ensure info returns a no-info packet if symbol not found"
    (let [response (session/message {:op "info" :symbol "awoeijfxcvb" :ns "user"})]
      (is (= (:status response) #{"no-info" "done"}))))

  (testing "ensure info returns a no-info packet if ns not found"
    (let [response (session/message {:op "info" :symbol "+" :ns "fakefakefake"})]
      (is (= (:status response) #{"no-info" "done"}))))

  (testing "ensure info returns a no-info packet if class not found"
    (let [response (session/message {:op "info" :class "awoeijfxcvb" :member "toString"})]
      (is (= (:status response) #{"no-info" "done"}))))

  (testing "ensure info returns a no-info packet if member not found"
    (let [response (session/message {:op "info" :class "java.lang.Exception" :member "fakefakefake"})]
      (is (= (:status response) #{"no-info" "done"})))))

(deftest missing-eldoc-test
  (testing "ensure eldoc returns a no-eldoc packet if symbol not found"
    (let [response (session/message {:op "eldoc" :symbol "awoeijfxcvb" :ns "user"})]
      (is (= (:status response) #{"no-eldoc" "done"}))))

  (testing "ensure eldoc returns a no-eldoc packet if ns not found"
    (let [response (session/message {:op "eldoc" :symbol "+" :ns "fakefakefake"})]
      (is (= (:status response) #{"no-eldoc" "done"}))))

  (testing "ensure eldoc returns a no-eldoc packet if class not found"
    (let [response (session/message {:op "eldoc" :class "awoeijfxcvb" :member "toString"})]
      (is (= (:status response) #{"no-eldoc" "done"}))))

  (testing "ensure eldoc returns a no-eldoc packet if member not found"
    (let [response (session/message {:op "eldoc" :class "java.lang.Exception" :member "fakefakefake"})]
      (is (= (:status response) #{"no-eldoc" "done"})))))

(deftest error-handling-test
  (testing "handle the exception thrown if no member provided to a java class info query"
    (let [response (session/message {:op "info" :class "test"})]
      (is (= (:status response) #{"info-error" "done"}))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (.startsWith (:err response) "java.lang.Exception: Either"))
      (is (:pp-stacktrace response))))

  (testing "handle the exception thrown if no member provided to a java class eldoc query"
    (let [response (session/message {:op "eldoc" :class "test"})]
      (is (= (:status response) #{"eldoc-error" "done"}))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (.startsWith (:err response) "java.lang.Exception: Either"))
      (is (:pp-stacktrace response))))

  (testing "handle the exception thrown if no class provided to a java member info query"
    (let [response (session/message {:op "info" :member "test"})]
      (is (= (:status response) #{"info-error" "done"}))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (.startsWith (:err response) "java.lang.Exception: Either"))
      (is (:pp-stacktrace response))))

  (testing "handle the exception thrown if no class provided to a java member eldoc query"
    (let [response (session/message {:op "eldoc" :member "test"})]
      (is (= (:status response) #{"eldoc-error" "done"}))
      (is (= (:ex response) "class java.lang.Exception"))
      (is (.startsWith (:err response) "java.lang.Exception: Either"))
      (is (:pp-stacktrace response))))

  (testing "handle the exception thrown if there's a mocked info retrieval error"
    (with-redefs [info/info (fn [& _] (throw (Exception. "info-exception")))]
      (let [response (session/message {:op "info" :symbol "test" :ns "user"})]
        (is (= (:status response) #{"info-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (.startsWith (:err response) "java.lang.Exception: info-exception"))
        (is (:pp-stacktrace response)))))

  (testing "handle the exception thrown if there's a mocked eldoc retreival error "
    (with-redefs [info/extract-arglists (fn [& _] (throw (Exception. "eldoc-exception")))]
      (let [response (session/message {:op "eldoc" :symbol "test" :ns "user"})]
        (is (= (:status response) #{"eldoc-error" "done"}))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (.startsWith (:err response) "java.lang.Exception: eldoc-exception"))
        (is (:pp-stacktrace response))))))

;;;; eldoc datomic query
(def testing-datomic-query '[:find ?x
                             :in $ ?name
                             :where
                             [?x :person/name ?name]])

(deftest eldoc-datomic-query-test
  (testing "eldoc of inline datomic query"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :symbol "'[:find ?x :in $ % ?person-id]"
                                     :ns "user"})]
      (is (= (:inputs response) '(["$" "%" "?person-id"])))))

  (testing "eldoc of inline datomic query as map"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :symbol "'{:find [?x] :in [$ % ?person-id]}"
                                     :ns "user"})]
      (is (= (:inputs response) '(["$" "%" "?person-id"])))))

  (testing "eldoc of datomic query defined as symbol"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :symbol "testing-datomic-query"
                                     :ns "cider.nrepl.middleware.info-test"})]
      (is (= (:inputs response) '(["$" "?name"])))))

  (testing "eldoc of inline datomic query without :in"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :symbol "'[:find ?x]"
                                     :ns "user"})]
      (is (= (:inputs response) '(["$"])))))

  (testing "eldoc of inline datomic query as map without :in"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :symbol "'{:find ?x}"
                                     :ns "user"})]
      (is (= (:inputs response) '(["$"])))))

  (testing "eldoc of empty datomic query"
    (let [response (session/message {:op "eldoc-datomic-query"
                                     :symbol ""
                                     :ns "user"})]
      (is (= (:status response) #{"no-eldoc" "done"})))))

;; Following comment is a fake. It mimics CLJX generated files.

;;;;;;;;;;;; This file autogenerated from simple/test/workaround.cljx
