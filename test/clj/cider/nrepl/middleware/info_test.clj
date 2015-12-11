(ns cider.nrepl.middleware.info-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.repl :as repl]
            [cider.nrepl.middleware.info :as info]))

(defn file
  [x]
  (:file (info/file-info x)))

(defn relative
  [x]
  (:resource (info/file-info x)))

(deftest test-resource-path
  (is (= (class (file (subs (str (clojure.java.io/resource "clojure/core.clj")) 4)))
         java.net.URL))
  (is (= (class (file "clojure/core.clj"))
         java.net.URL))
  (is (= (class (file "clojure-1.7.0.jar:clojure/core.clj"))
         java.net.URL))
  (is (= (class (file "test/clj/cider/nrepl/middleware/info_test.clj"))
         java.net.URL))
  (is (relative "clojure/core.clj"))
  (is (nil? (relative "notclojure/core.clj"))))

(deftest test-boot-resource-path
  (let [tmp-dir-name (System/getProperty "java.io.tmpdir")
        tmp-file-name "boot-test.txt"
        tmp-file-path (str tmp-dir-name (System/getProperty "file.separator") tmp-file-name)]
    (spit tmp-file-path "test")
    (testing "when fake.class.path is not set"
      (is (not (= (class (file tmp-file-name))
                  java.net.URL)))
      (is (= (file tmp-file-name) tmp-file-name)))
    (testing "when fake.class.path is set"
      (System/setProperty "fake.class.path" tmp-dir-name)
      (is (= (class (file tmp-file-name))
             java.net.URL))
      (is (= (.getPath (file tmp-file-name))
             tmp-file-path)))))

(deftype T [])

(deftest test-info
  (is (info/info-clj 'cider.nrepl.middleware.info 'io))

  (is (info/info-clj 'cider.nrepl.middleware.info 'info-clj))

  (is (info/info-clj 'cider.nrepl.middleware.info 'java.lang.Class))
  (is (info/info-clj 'cider.nrepl.middleware.info 'Class/forName))
  (is (info/info-clj 'cider.nrepl.middleware.info '.toString))

  (is (not (info/info-clj 'clojure.core (gensym "non-existing"))))
  (is (not (info/info-clj 'cider.nrepl.middleware.info-test (gensym "non-existing")))
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

  ;; test CLJX resolve
  (is (= "simple/test/workaround.cljx"
         (-> (info/info-clj 'cider.nrepl.middleware.info-test 'test-info)
             info/handle-cljx-sources
             :file)))

  ;; either symbol or (class method) should be passed
  (is (thrown? Exception
               (info/info {:ns "cider.nrepl.middleware.info-test"
                           :class "Thread"})))

  ;; this is a replacement for (is (not (thrown? ..)))
  (is (nil? (info/info {:class "Thread" :member "UncaughtExceptionHandler"}))))

(deftest test-response
  (let [v (ns-resolve 'cider.nrepl.middleware.info 'assoc)
        {:keys [arglists column line added static doc]} (meta v)]
    (is (= (dissoc (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'assoc)) "file")
           {"ns" "clojure.core"
            "name" "assoc"
            "arglists-str" (pr-str arglists)
            "column" column
            "added" added
            "static" (str static)
            "doc" doc
            "line" line
            "resource" "clojure/core.clj"}))))

;;;; eldoc
(def test-eldoc-info {:arglists '([x] [x y])})

(def test-eldoc-info-candidates
  {:candidates '{X {:arglists ([x])}
                 Y {:arglists ([x] [x y z])}
                 Z {:arglists ([])}}})

(deftest test-extract-eldoc
  (is (= (info/extract-eldoc test-eldoc-info) '([x] [x y])))
  (is (= (info/extract-eldoc test-eldoc-info-candidates)
         '([] [x] [x y z]))))

(deftest test-format-eldoc
  (is (= (info/format-eldoc (info/extract-eldoc test-eldoc-info)) '(["x"] ["x" "y"])))
  (is (= (info/format-eldoc (info/extract-eldoc test-eldoc-info-candidates))
         '([] ["x"] ["x" "y" "z"]))))

(deftest test-eldoc
  (is (info/eldoc {:ns "clojure.core" :symbol "map"}))
  (is (info/eldoc {:ns "clojure.core" :symbol ".toString"}))
  (is (not (info/eldoc {:ns "clojure.core" :symbol (gensym "non-existing")}))))

(deftest test-var-meta
  ;; Test files can't be found on the class path.
  (is (:file (info/var-meta #'info/var-meta)))
  (is (re-find #"cider-nrepl" (:file (#'info/maybe-add-file {:ns (find-ns 'cider.nrepl.middleware.info)}))))
  (is (not (re-find #"/form-init[^/]*$" (:file (info/var-meta (eval '(do (in-ns 'cider.nrepl.middleware.info) (def pok 10)))))))))

;; Following comment is a fake. It mimics CLJX generated files.

;;;;;;;;;;;; This file autogenerated from simple/test/workaround.cljx
