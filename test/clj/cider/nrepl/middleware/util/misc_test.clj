(ns cider.nrepl.middleware.util.misc-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [cider.nrepl.middleware.util.misc :as misc]))

(deftest boot-fake-classpath-test
  (let [fake-paths [(System/getProperty "java.io.tmpdir")]
        fake-classpath (str/join ":" fake-paths)]
    (testing "when fake.class.path is not set"
      (is (nil? (misc/boot-fake-classpath)))
      (is (not (misc/boot-project?))))
    (testing "when fake.class.path is set"
      (try
        (System/setProperty "fake.class.path" fake-classpath)
        (is (= fake-classpath (misc/boot-fake-classpath)))
        (is (misc/boot-project?))
        (finally
          (System/clearProperty "fake.class.path"))))))

(deftest transform-value-test
  (is (= (misc/transform-value (array-map
                                :a "b"
                                "c" :d
                                :e false
                                :f [:g :h :i]
                                :j 4
                                :k nil))
         '{"a" "b", "c" "d", "e" "false", "f" ("g" "h" "i"), "j" 4, "k" nil}))
  (is (-> (misc/transform-value {:k (java.io.File. ".")})
          (get "k")
          java.io.File.
          .isAbsolute)))

(deftest as-sym-test
  (is (= nil (misc/as-sym nil)))
  (is (= nil (misc/as-sym 1)))
  (is (= nil (misc/as-sym '())))
  (is (= nil (misc/as-sym [])))
  (is (= nil (misc/as-sym {})))
  (is (= nil (misc/as-sym :WAT)))
  (is (= 'WAT (misc/as-sym "WAT")))
  (is (= 'WAT (misc/as-sym 'WAT))))

(deftest update-vals-test
  (is (= (misc/update-vals inc {1 2 3 4 5 6})
         {1 3 3 5 5 7}))
  (is (= (misc/update-vals range {1 2 3 4 5 6})
         '{5 (0 1 2 3 4 5), 3 (0 1 2 3), 1 (0 1)}))
  (is (= (misc/update-vals str {:a :b :c :d :e :f})
         {:e ":f", :c ":d", :a ":b"}))
  (is (= (misc/update-vals odd? {1 2 3 4 5 6})
         {1 false 3 false 5 false})))

(deftest update-keys-test
  (is (= (misc/update-keys inc {1 2 3 4 5 6})
         {2 2, 4 4, 6 6}))
  (is (= (misc/update-keys range {1 2 3 4 5 6})
         '{(0) 2, (0 1 2) 4, (0 1 2 3 4) 6}))
  (is (= (misc/update-keys str {:a :b :c :d :e :f})
         {":a" :b, ":c" :d, ":e" :f})))
