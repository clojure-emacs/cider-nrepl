(ns cider-nrepl.plugin-test
  (:require [cider-nrepl.plugin :as plugin]
            [cider.nrepl.middleware :refer [cider-middleware]]
            [clojure.test :refer :all]
            [leiningen.core.main :as lein]))

(defn- contains-cider-nrepl-dep? [{:keys [dependencies]}]
  (boolean (->> dependencies
                (some (fn [[id version & _]]
                        (= 'cider/cider-nrepl id))))))

(defn- contains-cider-nrepl-middleware? [{{:keys [nrepl-middleware]} :repl-options}]
  (= cider-middleware nrepl-middleware))

(deftest plugin-test
  ;; Suppress output of leiningen.core.main/warn
  (binding [lein/*info* false]
    (with-redefs [lein/leiningen-version (constantly (plugin/minimum-versions :lein))]
      (testing "Valid Lein version; valid Clojure version"
        (let [project (plugin/middleware '{:dependencies [[org.clojure/clojure "1.10.0"]]})]
          (is (contains-cider-nrepl-dep? project))
          (is (contains-cider-nrepl-middleware? project))))

      (testing "Valid Lein version; no Clojure version specified"
        (let [project (plugin/middleware '{})]
          (is (contains-cider-nrepl-dep? project))
          (is (contains-cider-nrepl-middleware? project))))

      (testing "Valid Lein version; multiple Clojure versions specified"
        (let [project (plugin/middleware `{:dependencies [[org.clojure/clojure "1.5.1"]
                                                          [org.clojure/clojure ~(plugin/minimum-versions :clojure)]]})]
          (is (contains-cider-nrepl-dep? project))
          (is (contains-cider-nrepl-middleware? project))))

      (testing "Valid Lein version; invalid Clojure version"
        (let [project (plugin/middleware '{:dependencies [[org.clojure/clojure "1.6.0"]]})]
          (is (not (contains-cider-nrepl-dep? project)))
          (is (not (contains-cider-nrepl-middleware? project))))))

    (with-redefs [lein/leiningen-version (constantly "2.5.1")]
      (testing "Invalid Lein version; valid Clojure version"
        (let [project (plugin/middleware '{:dependencies [[org.clojure/clojure "1.10.0"]]})]
          (is (not (contains-cider-nrepl-dep? project)))
          (is (not (contains-cider-nrepl-middleware? project)))))

      (testing "Invalid Lein version; no Clojure version specified"
        (let [project (plugin/middleware '{})]
          (is (not (contains-cider-nrepl-dep? project)))
          (is (not (contains-cider-nrepl-middleware? project)))))

      (testing "Invalid Lein version; invalid Clojure version"
        (let [project (plugin/middleware '{:dependencies [[org.clojure/clojure "1.6.0"]]})]
          (is (not (contains-cider-nrepl-dep? project)))
          (is (not (contains-cider-nrepl-middleware? project))))))))
