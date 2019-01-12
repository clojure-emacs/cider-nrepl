(ns cider.nrepl.middleware.profile-test
  (:require
   [cider.nrepl.middleware.profile :refer :all]
   [cider.nrepl.test-transport :refer [test-transport]]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(defn with-clear-profile
  [f]
  (f)
  (clear-profile {:transport (test-transport)}))

(use-fixtures :each with-clear-profile)

(deftest toggle-profile-test
  (testing "profile toggling"
    (is (= [{:value "profiled" :status #{:done}}]
           (toggle-profile {:ns "clojure.core" :sym "zipmap" :transport (test-transport)})))
    (is (= [{:value "unprofiled" :status #{:done}}]
           (toggle-profile {:ns "clojure.core" :sym "zipmap" :transport (test-transport)})))))

(deftest profile-var-summary-test
  (testing "Var profile sumary"
    (toggle-profile {:ns "clojure.core" :sym "zipmap" :transport (test-transport)})
    (zipmap [:a :b :c] [1 2 3])
    (let [[{:keys [err status]}] (profile-var-summary {:ns        "clojure.core"
                                                       :sym       "zipmap"
                                                       :transport (test-transport)})]
      (is (.startsWith err "#'clojure.core/zipmap"))
      (is (= #{:done} status)))
    (toggle-profile {:ns "clojure.core" :sym "zipmap" :transport (test-transport)})
    (clear-profile {:transport (test-transport)}))

  (testing "No Var bound"
    (is (= [{:value "Var clojure.core/not-existent is not bound." :status #{:done}}]
           (profile-var-summary {:ns "clojure.core" :sym "not-existent" :transport (test-transport)})))))

(deftest toggle-profile-ns-test
  (testing "toggling profile ns"
    (is (= [{:value "profiled" :status #{:done}}]
           (toggle-profile-ns {:ns "clojure.string" :transport (test-transport)})))
    (is (= [{:value "unprofiled" :status #{:done}}]
           (toggle-profile-ns {:ns "clojure.string" :transport (test-transport)}))))

  (testing "unbounding profile"
    (is (= [{:value "exception" :status #{:done}}]
           (toggle-profile-ns {:ns "my.ns" :transport (test-transport)})))))

(deftest is-var-profiled-test
  (testing "is var profiled"
    (toggle-profile {:ns "clojure.core" :sym "zipmap" :transport (test-transport)})
    (is (= [{:value "profiled" :status #{:done}}]
           (is-var-profiled {:ns "clojure.core" :sym "zipmap" :transport (test-transport)})))
    (toggle-profile {:ns "clojure.core" :sym "zipmap" :transport (test-transport)})
    (is (= [{:value "unprofiled" :status #{:done}}]
           (is-var-profiled {:ns "clojure.core" :sym "zipmap" :transport (test-transport)})))))

(deftest set-max-examples-test
  (testing "max examples"
    (is (= [{:value "5000" :status #{:done}}]
           (set-max-samples {:max-samples 5000 :transport (test-transport)})))))
