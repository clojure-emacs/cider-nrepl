(ns cider.nrepl.middleware.ns-test
  (:require
   [cider.nrepl.middleware.ns :refer [ns-vars-clj ns-list-vars-by-name] :as cider-ns]
   [cider.nrepl.test-session :as session]
   [cider.test-ns first-test-ns second-test-ns third-test-ns resolve-missing-ns]
   [clojure.set :as set]
   [clojure.test :refer :all]))

(deftest toogle-ns-vars-test
  (let [ns "clojure.core"]
    (is (= (count (ns-publics (symbol ns))) (count (ns-vars-clj ns))))))

;; integration tests

(use-fixtures :each session/session-fixture)

(deftest ns-list-integration-test
  (testing "Basic checks"
    (let [ns-list (:ns-list (session/message {:op "cider/ns-list"}))]
      (is (sequential? ns-list))
      (is (every? string? ns-list))))

  (testing "Removal of namespaces created by source rewriting"
    (let [ns-list (:ns-list (session/message {:op "cider/ns-list"}))]
      (is (not-any? #(or (.startsWith ^String % "deps.")
                         (.startsWith ^String % "mranderson")
                         (.startsWith ^String % "eastwood.copieddeps"))
                    ns-list))))

  (testing "Removal of namespaces with `filter-regexps`"
    (let [ns-list (:ns-list (session/message {:op "cider/ns-list"
                                              :filter-regexps [".*nrepl"]}))]
      (is (not-any? #(re-find #".*nrepl" %) ns-list)))))

(deftest ns-list-vars-by-name-integration-test
  (let [response (session/message {:op "cider/ns-list-vars-by-name"
                                   :name "same-name-testing-function"})
        var-list (read-string (:var-list response))]
    (is (= (set var-list)
           #{'(var cider.test-ns.first-test-ns/same-name-testing-function)
             '(var cider.test-ns.second-test-ns/same-name-testing-function)
             '(var cider.test-ns.third-test-ns/same-name-testing-function)}))))

(deftest ns-vars-integration-test
  (let [ns-vars (:ns-vars (session/message {:op "cider/ns-vars"
                                            :ns "clojure.walk"}))]
    (is (sequential? ns-vars))
    (is (every? string? ns-vars))))

(deftest ns-vars-including-privates-test
  (testing "Without private vars"
    (let [ns-vars (:ns-vars (session/message {:op "cider/ns-vars"
                                              :ns "clojure.core"}))]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))
      (is (nil? (some #(= "is-annotation?" %) ns-vars)))))

  (testing "Including private vars"
    (let [ns-vars (:ns-vars (session/message {:op "cider/ns-vars"
                                              :ns "clojure.core"
                                              :var-query {:private? 1}}))]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))
      (is (some #(= "is-annotation?" %) ns-vars)))))

(deftest ns-vars-with-meta-integration-test
  (let [ns-vars-with-meta (:ns-vars-with-meta
                           (session/message {:op "cider/ns-vars-with-meta"
                                             :ns "clojure.core"}))]
    (is (every? (comp map? second) ns-vars-with-meta))
    (is (= (:+ ns-vars-with-meta)
           {:arglists "([] [x] [x y] [x y & more])"
            :doc "\"Returns the sum of nums. (+) returns 0. Does not auto-promote\\n  longs, will throw on overflow. See also: +'\""}))
    (is (= (:doseq ns-vars-with-meta)
           {:arglists "([seq-exprs & body])"
            :macro "true"
            :doc "\"Repeatedly executes body (presumably for side-effects) with\\n  bindings and filtering as provided by \\\"for\\\".  Does not retain\\n  the head of the sequence. Returns nil.\""}))
    (is (= (:*ns* ns-vars-with-meta)
           {:doc "\"A clojure.lang.Namespace object representing the current namespace.\""}))))

(deftest ns-vars-with-meta-including-privates-test
  (testing "Without private vars"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "cider/ns-vars-with-meta"
                                               :ns "clojure.core"}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (nil? (:is-annotation? ns-vars-with-meta)))))

  (testing "Including private vars"
    (let [ns-vars-with-meta (:ns-vars-with-meta
                             (session/message {:op "cider/ns-vars-with-meta"
                                               :ns "clojure.core"
                                               :var-query {:private? 1}}))]
      (is (every? (comp map? second) ns-vars-with-meta))
      (is (= (:is-annotation? ns-vars-with-meta)
             {:arglists "([c])"})))))

(deftest ns-path-integration-test
  (let [{ns-path :path
         ns-url :url} (session/message {:op "cider/ns-path"
                                        :ns "cider.nrepl.middleware.ns"})
        {core-path :path
         core-url :url} (session/message {:op "cider/ns-path"
                                          :ns "clojure.core"})]
    (is (.endsWith ^String ns-path "cider/nrepl/middleware/ns.clj"))
    (is (.endsWith ^String core-path "clojure/core.clj"))
    (is (.startsWith ^String core-path "jar:"))
    (is (= ns-path ns-url))
    (is (= core-path core-url))))

(deftest ns-load-all-integration-test
  (let [loaded-ns (:loaded-ns (session/message {:op "cider/ns-load-all"}))]
    (is (sequential? loaded-ns))
    (is (every? string? loaded-ns))))

(deftest ns-list-vars-by-name-test
  (is (= (first (ns-list-vars-by-name 'ns-list-vars-by-name-test))
         #'cider.nrepl.middleware.ns-test/ns-list-vars-by-name-test))
  (is (= (count (ns-list-vars-by-name 'ns-list-vars-by-name-test)) 1))
  (is (not (seq (ns-list-vars-by-name 'all-your-base-are-belong-to-us)))))

(deftest ns-aliases-integration-test
  (let [aliases (:ns-aliases (session/message {:op "cider/ns-aliases"
                                               :ns "cider.nrepl.middleware.ns-test"}))]
    (is (map? aliases))
    (is (= (:cider-ns aliases) "cider.nrepl.middleware.ns"))))

(deftest error-handling-test
  (testing "ns-list op error handling"
    (with-redefs [cider-ns/ns-list (fn [& _] (throw (Exception. "ns-list error")))]
      (let [response (session/message {:op "cider/ns-list"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-list error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-list-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-list-vars-by-name op error handling"
    (with-redefs [cider-ns/ns-list-vars-by-name (fn [& _] (throw (Exception. "ns-list-vars-by-name error")))]
      (let [response (session/message {:op "cider/ns-list-vars-by-name"
                                       :name "testing-function"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-list-vars-by-name error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-list-vars-by-name-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-vars op error handling"
    (with-redefs [cider-ns/ns-vars (fn [& _] (throw (Exception. "ns-vars error")))]
      (let [response (session/message {:op "cider/ns-vars"
                                       :name "testing-function"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-vars error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-vars-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-path op error handling"
    (with-redefs [cider-ns/ns-path (fn [& _] (throw (Exception. "ns-path error")))]
      (let [response (session/message {:op "cider/ns-path"
                                       :name "testing-function"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-path error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-path-error" "done"}))
        (is (:pp-stacktrace response)))))

  (testing "ns-aliases op error handling"
    (with-redefs [cider-ns/ns-aliases (fn [& _] (throw (Exception. "ns-aliases error")))]
      (let [response (session/message {:op "cider/ns-aliases" :name "testing-function"})]
        (is (-> response ^String (:err) (.startsWith "java.lang.Exception: ns-aliases error")))
        (is (= (:ex response) "class java.lang.Exception"))
        (is (= (:status response) #{"cider/ns-aliases-error" "done"}))
        (is (:pp-stacktrace response))))))

(deftest deprecated-ops-test
  (testing "Deprecated 'ns-list' op still works"
    (let [ns-list (:ns-list (session/message {:op "ns-list"}))]
      (is (sequential? ns-list))
      (is (every? string? ns-list))))

  (testing "Deprecated 'ns-vars' op still works"
    (let [ns-vars (:ns-vars (session/message {:op "ns-vars"
                                              :ns "clojure.walk"}))]
      (is (sequential? ns-vars))
      (is (every? string? ns-vars))))

  (testing "Deprecated 'ns-path' op still works"
    (let [{ns-path :path} (session/message {:op "ns-path"
                                            :ns "cider.nrepl.middleware.ns"})]
      (is (.endsWith ^String ns-path "cider/nrepl/middleware/ns.clj"))))

  (testing "Deprecated 'ns-aliases' op still works"
    (let [aliases (:ns-aliases (session/message {:op "ns-aliases"
                                                 :ns "cider.nrepl.middleware.ns-test"}))]
      (is (map? aliases)))))

(defn- resolve-missing [sym & [ns]]
  (set (cider-ns/resolve-missing {:symbol sym :ns ns})))

(deftest resolve-missing-test
  (testing "Namespaces with a public var of that name"
    (is (set/subset? #{{:name "cider.test-ns.first-test-ns" :type "ns"}
                       {:name "cider.test-ns.second-test-ns" :type "ns"}}
                     (resolve-missing "same-name-testing-function")))
    (is (contains? (resolve-missing "some-alias/same-name-testing-function")
                   {:name "cider.test-ns.first-test-ns" :type "ns"}))
    (is (not-any? #(= "cider.test-ns.resolve-missing-ns" (:name %))
                  (resolve-missing "private-function"))))

  (testing "Classes of that name"
    (is (set/subset? #{{:name "java.util.Date" :type "class"}
                       {:name "java.sql.Date" :type "class"}}
                     (resolve-missing "Date")))
    (is (= (resolve-missing "Date") (resolve-missing "Date.")))
    (is (contains? (resolve-missing "Instant/now")
                   {:name "java.time.Instant" :type "class"}))
    (is (empty? (resolve-missing "java.util.Date")))
    (is (not-any? #(= "class" (:type %)) (resolve-missing "some-alias/Date"))))

  (testing "Types and records come with the namespace that defines them"
    (is (contains? (resolve-missing "ResolvableType")
                   {:name "cider.test_ns.resolve_missing_ns.ResolvableType"
                    :type "type"
                    :ns "cider.test-ns.resolve-missing-ns"}))
    (is (contains? (resolve-missing "ResolvableRecord.")
                   {:name "cider.test_ns.resolve_missing_ns.ResolvableRecord"
                    :type "type"
                    :ns "cider.test-ns.resolve-missing-ns"})))

  (testing "A symbol the namespace can already use gets nothing"
    (let [in-ns #(resolve-missing % "cider.test-ns.resolve-missing-ns")]
      ;; Offering the other namespaces would only change what it means.
      (is (empty? (in-ns "same-name-testing-function")))
      (is (empty? (in-ns "first-ns/some-test-var")))
      (is (empty? (in-ns "cider.test-ns.resolve-missing-ns/local-function")))
      ;; It only resolves because something else loaded the namespace.
      (is (= #{{:name "cider.test-ns.third-test-ns" :type "ns"}}
             (in-ns "cider.test-ns.third-test-ns/same-name-testing-function")))
      (is (contains? (in-ns "other/some-test-var")
                     {:name "cider.test-ns.first-test-ns" :type "ns"}))
      (is (empty? (in-ns "Date")))
      (is (empty? (in-ns "Date.")))
      (is (empty? (in-ns "Date/from")))
      (is (empty? (in-ns "java.time.Instant/now")))
      (is (empty? (in-ns "map")))
      (is (empty? (in-ns "local-function")))
      (is (empty? (in-ns "ResolvableType")))))

  (testing "A namespace that isn't loaded filters nothing"
    (is (= (resolve-missing "Date") (resolve-missing "Date" "not.a.loaded.ns")))
    (is (= (resolve-missing "Date") (resolve-missing "Date" ""))))

  (testing "A fully qualified symbol leaves no choice"
    (is (= #{{:name "cider.test-ns.first-test-ns" :type "ns"}}
           (resolve-missing "cider.test-ns.first-test-ns/same-name-testing-function")))
    ;; Even when the namespace lacks the var: another namespace with that name
    ;; as its alias would change what the name means.
    (is (empty? (resolve-missing "clojure.walk/same-name-testing-function")))
    (is (empty? (resolve-missing "clojure.core/map" "cider.test-ns.resolve-missing-ns"))))

  (testing "Special forms resolve"
    (let [temp 'cider.nrepl.resolve-missing-special-test]
      (try
        (intern (create-ns temp) 'catch identity)
        (is (contains? (resolve-missing "catch") {:name (str temp) :type "ns"}))
        (is (empty? (resolve-missing "catch" "cider.test-ns.resolve-missing-ns")))
        (finally
          (remove-ns temp)))))

  (testing "Looking up a class doesn't initialize it"
    (is (empty? (resolve-missing "cider.nrepl.test.StaticInitTestClass/now"
                                 "cider.test-ns.resolve-missing-ns")))
    (is (nil? (System/getProperty "cider.nrepl.test.StaticInitTestClass"))))

  (testing "A class before the slash makes it a static member, not an alias"
    (is (every? #(= "class" (:type %)) (resolve-missing "Instant/now")))
    (is (empty? (resolve-missing "String/join" "cider.test-ns.resolve-missing-ns"))))

  (testing "The JDK's internal classes are left out"
    (let [candidates (resolve-missing "StringReader")]
      (is (contains? candidates {:name "java.io.StringReader" :type "class"}))
      (is (not-any? #(re-find #"^(jdk\.internal|sun)\.|\.internal\." (:name %)) candidates))))

  (testing "Inlined dependencies are left out"
    (let [inlined 'cider.nrepl.inlined.deps.resolve-missing-test]
      (try
        (binding [*ns* (create-ns inlined)]
          (eval '(do (clojure.core/refer-clojure)
                     (defn same-name-testing-function [])
                     (deftype InlinedResolvableType []))))
        (is (not-any? #(= (str inlined) (:name %))
                      (resolve-missing "same-name-testing-function")))
        (is (empty? (resolve-missing "InlinedResolvableType")))
        (finally
          (remove-ns inlined)))))

  (testing "A blank symbol is an error"
    (is (thrown? IllegalArgumentException (resolve-missing " ")))))

(deftest resolve-missing-cljs-env-test
  (testing "A namespace without a name in the compiler env gets skipped"
    (is (= [{:name "a.b" :type "ns"}]
           (#'cider-ns/resolve-missing-cljs
            {:cljs.analyzer/namespaces {nil {:defs {}}
                                        'a.b {:defs {'join {:name 'a.b/join}}}}}
            nil nil "join"))))

  (testing "A renamed refer resolves"
    (let [env {:cljs.analyzer/namespaces
               {'foo.ren {:renames {'union 'clojure.string/join}}
                'clojure.set {:defs {'union {:name 'clojure.set/union}}}}}]
      (is (empty? (#'cider-ns/resolve-missing-cljs env 'foo.ren nil "union")))
      (is (= [{:name "clojure.set" :type "ns"}]
             (#'cider-ns/resolve-missing-cljs
              (update-in env [:cljs.analyzer/namespaces 'foo.ren] dissoc :renames)
              'foo.ren nil "union"))))))

(deftest resolve-missing-integration-test
  (let [{:keys [candidates status]} (session/message {:op "cider/resolve-missing"
                                                      :symbol "other/same-name-testing-function"
                                                      :ns "cider.test-ns.resolve-missing-ns"})]
    (is (= #{"done"} status))
    (is (= [{:name "cider.test-ns.first-test-ns" :type "ns"}
            {:name "cider.test-ns.second-test-ns" :type "ns"}
            {:name "cider.test-ns.third-test-ns" :type "ns"}]
           candidates)))

  (testing "A blank symbol is an error"
    (is (= #{"cider/resolve-missing-error" "done"}
           (:status (session/message {:op "cider/resolve-missing" :symbol ""})))))

  (testing "There's no unqualified alias, which would shadow refactor-nrepl's op"
    (is (contains? (:status (session/message {:op "resolve-missing" :symbol "Date"}))
                   "unknown-op"))))

(defn- write-file [^java.io.File dir path content]
  (let [f (java.io.File. dir ^String path)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn- call-with-project
  "Call `f` with a temporary directory holding `files`, which `resolve-alias`
  takes for the project's sources."
  [files f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "resolve-alias" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path content] files]
        (write-file dir path content))
      (with-redefs [cider-ns/project-source-dirs (constantly [dir])]
        (f dir))
      (finally
        (doseq [^java.io.File file (reverse (file-seq dir))]
          (.delete file))))))

(def ^:private project-files
  {"proj/a.clj" "(ns proj.a (:require [clojure.walk :as rawk] [clojure.set :as zset]))"
   "proj/b.clj" "(ns proj.b (:require [clojure.walk :as rawk]))"
   "proj/c.clj" "(ns proj.c (:require [clojure.zip :as rawk]))"
   "proj/d.cljs" "(ns proj.d (:require [goog.string :as gstr] [\"react\" :as react] [clojure.walk :as rawk]))"
   "proj/e.cljc" "(ns proj.e (:require [clojure.set :as zset] #?(:clj [clojure.java.io :as jio] :cljs [goog.string :as jio])))"
   "proj/server.clj" "(ns proj.server)"
   "proj/f.clj" "(ns proj.f (:require [proj.server :as srv]))"
   "proj/billing/core.clj" "(ns proj.billing.core)"
   "proj/test/util.clj" "(ns proj.test.util)"
   "proj/h.cljc" "#?(:clj (ns proj.h (:require [clojure.set :as hset])))\n#?(:cljs (ns proj.h (:require [clojure.set :as hset])))"
   "proj/l1.clj" "(ns proj.l1 (:require [clojure.walk :as lg]))"
   "proj/l2.clj" "(ns proj.l2 (:require [clojure.walk :as lg]))"
   "proj/l3.cljc" "(ns proj.l3 (:require [clojure.set :as lg]))"
   "proj/bad_splice.clj" "(ns proj.bad-splice (:require #?@(:clj foo)))"
   "proj/bad_name.clj" "(ns \"proj.bad\" (:require [clojure.walk :as rawk]))"
   ".hidden/proj/g.clj" "(ns proj.g (:require [clojure.walk :as rawk]))"
   "proj/node_modules/n.clj" "(ns proj.n (:require [clojure.walk :as rawk]))"
   "proj/target/t.clj" "(ns proj.t (:require [clojure.walk :as rawk]))"
   "proj/notes.txt" "(ns proj.notes (:require [clojure.walk :as rawk]))"})

(defn- resolve-alias
  ([alias] (resolve-alias alias {}))
  ([alias msg] (cider-ns/resolve-alias (assoc msg :alias alias))))

(deftest resolve-alias-test
  (call-with-project
   project-files
   (fn [dir]
     (testing "The namespaces the project gives the alias, the most used first"
       (is (= [{:ns "clojure.walk" :source "project" :count 2}
               {:ns "clojure.zip" :source "project" :count 1}]
              (resolve-alias "rawk"))))

     (testing "Only the files for the platform count"
       (is (= [{:ns "clojure.walk" :source "project" :count 1}]
              (resolve-alias "rawk" {:platform "cljs"})))
       (is (= [{:ns "goog.string" :source "project" :count 1}]
              (resolve-alias "gstr" {:platform "cljs"})))
       (is (= [{:ns "clojure.java.io" :source "project" :count 1}]
              (resolve-alias "jio")))
       (is (= [{:ns "goog.string" :source "project" :count 1}]
              (resolve-alias "jio" {:platform "cljs"}))))

     (testing "A JavaScript module comes quoted, and only for ClojureScript"
       (is (= [{:ns "\"react\"" :source "project" :count 1}]
              (resolve-alias "react" {:platform "cljs"})))
       (is (empty? (resolve-alias "react"))))

     (testing "For cljc, a candidate has to work for both platforms"
       (is (= [{:ns "clojure.walk" :source "project" :count 3}
               {:ns "clojure.zip" :source "project" :count 1}]
              (resolve-alias "rawk" {:platform "cljc"})))
       (is (= [{:ns "clojure.set" :source "project" :count 2}]
              (resolve-alias "zset" {:platform "cljc"}))
           "a .cljc file counts once, though it's read for each platform")
       (with-redefs [cider-ns/cljs-on-classpath? (constantly true)]
         (is (empty? (resolve-alias "srv" {:platform "cljc"}))
             "proj.server is only defined for Clojure"))
       (with-redefs [cider-ns/cljs-on-classpath? (constantly false)]
         (is (= [{:ns "proj.server" :source "project" :count 1}]
                (resolve-alias "srv" {:platform "cljc"}))
             "without ClojureScript there's no telling")))

     (testing "A .cljc file can have a separate ns form for each platform"
       (is (= [{:ns "clojure.set" :source "project" :count 1}]
              (resolve-alias "hset" {:platform "cljs"}))))

     (testing "For cljc, what the project uses on both platforms comes first"
       (with-redefs [cider-ns/cljs-on-classpath? (constantly false)]
         (is (= [{:ns "clojure.set" :source "project" :count 1}
                 {:ns "clojure.walk" :source "project" :count 2}]
                (resolve-alias "lg" {:platform "cljc"})))))

     (testing "The current namespace's own file doesn't count"
       (is (= [{:ns "clojure.set" :source "project" :count 1}]
              (resolve-alias "zset" {:ns "proj.a"}))))

     (testing "An alias the project doesn't use gets the preferred namespaces that are there"
       (is (= [{:ns "clojure.data" :source "preferred"}]
              (resolve-alias "zzz" {:preferred ["clojure.data" "no.such.ns"]})))
       (is (= [{:ns "clojure.data" :source "preferred"}]
              (resolve-alias "zzz" {:preferred "clojure.data"})))
       (is (= [{:ns "\"react\"" :source "preferred"}
               {:ns "goog.object" :source "preferred"}]
              (resolve-alias "zzz" {:platform "cljs" :preferred ["\"react\"" "goog.object"]}))
           "a JavaScript module comes quoted, and the Closure Library is there")
       (is (empty? (resolve-alias "zzz" {:preferred [1]}))))

     (testing "and the project namespaces named after it"
       (is (= [{:ns "proj.billing.core" :source "suggested"}]
              (resolve-alias "billing")))
       (is (= [{:ns "clojure.data" :source "preferred"}
               {:ns "proj.billing.core" :source "suggested"}]
              (resolve-alias "billing" {:preferred ["clojure.data"]})))
       (is (empty? (resolve-alias "billing" {:ns "proj.billing.core"}))
           "but not the namespace itself")
       (is (empty? (resolve-alias "util"))
           "nor a test namespace"))

     (testing "What the project uses wins over the preferred namespaces"
       (is (= ["clojure.walk" "clojure.zip"]
              (map :ns (resolve-alias "rawk" {:preferred ["clojure.data"]})))))

     (testing "An alias the namespace has already, or that's something else, gets nothing"
       (is (empty? (resolve-alias "str" {:ns "cider.nrepl.middleware.ns"})))
       (is (empty? (resolve-alias "clojure.walk")))
       (is (empty? (resolve-alias "Math")))
       (is (empty? (resolve-alias "js" {:platform "cljs"}))))

     (testing "Changed and deleted files are read again"
       (let [a (java.io.File. dir "proj/a.clj")]
         (spit a "(ns proj.a (:require [clojure.zip :as rawk]))")
         (.setLastModified a (+ (.lastModified a) 2000)))
       (.delete (java.io.File. dir "proj/b.clj"))
       (is (= [{:ns "clojure.zip" :source "project" :count 2}]
              (resolve-alias "rawk"))))))

  (testing "A blank alias is an error"
    (is (thrown? IllegalArgumentException (resolve-alias " ")))))

(deftest project-source-dirs-test
  (let [dirs (map #(.getName ^java.io.File %) (#'cider-ns/project-source-dirs))]
    (is (some #{"src"} dirs))
    (is (not-any? #{"resources" "test-resources"} dirs))))

(deftest resolve-alias-integration-test
  (let [{:keys [candidates status]} (session/message {:op "cider/resolve-alias"
                                                      :alias "str"
                                                      :ns "user"})]
    (is (= #{"done"} status))
    (is (= {:ns "clojure.string" :source "project"}
           (dissoc (first candidates) :count))))

  (testing "A blank alias is an error"
    (is (= #{"cider/resolve-alias-error" "done"}
           (:status (session/message {:op "cider/resolve-alias" :alias ""}))))))

(deftest clean-ns-integration-test
  (let [clean-ns #(session/message (merge {:op "cider/clean-ns"} %))]
    (testing "what can go"
      (let [{:keys [libspecs imports status]}
            (clean-ns {:platform "clj"
                       :code (str "(ns foo\n"
                                  "  (:require [clojure.set :as set]\n"
                                  "            [clojure.string :as str :refer [join split]]\n"
                                  "            [clojure.walk :as walk]\n"
                                  "            [\"react\" :as react])\n"
                                  "  (:import (java.util Date List)))\n"
                                  "(join (clojure.walk/postwalk identity []) (Date.))")})]
        (is (= #{"done"} status))
        (is (= [{:ns "clojure.set" :clause "require" :remove "true"}
                {:ns "clojure.string" :clause "require" :as "str" :refer ["split"]}
                {:ns "clojure.walk" :clause "require" :as "walk"}
                {:ns "\"react\"" :clause "require" :remove "true"}]
               libspecs))
        (is (= ["java.util.List"] imports))))

    (testing "the platform"
      (let [code "(ns foo (:require [react :as react])) (.createElement react \"div\")"]
        (is (= [{:ns "react" :clause "require" :remove "true"}]
               (:libspecs (clean-ns {:code code :platform "clj"}))))
        (is (empty? (:libspecs (clean-ns {:code code :platform "cljs"}))))
        (is (empty? (:libspecs (clean-ns {:code code}))))))

    (testing "nothing to remove"
      (let [{:keys [libspecs imports status]} (clean-ns {:code "(ns foo (:require [clojure.set :as set])) set/union"})]
        (is (= #{"done"} status))
        (is (empty? libspecs))
        (is (empty? imports))))

    (testing "code that doesn't read"
      (is (= {:status #{"cider/clean-ns-error" "done"}
              :error "Unmatched delimiter: )"
              :line 12}
             (select-keys (clean-ns {:code "(ns foo)\n\n(str/join []))" :line 10})
                          [:status :error :line]))))

    (testing "no ns form"
      (is (= {:status #{"cider/clean-ns-error" "done"}
              :error "No ns form to clean"}
             (select-keys (clean-ns {:code "(defn foo [])"}) [:status :error :line]))))))
