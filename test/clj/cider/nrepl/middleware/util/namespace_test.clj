(ns cider.nrepl.middleware.util.namespace-test
  (:require [clojure.test :refer :all])
  (:require [cider.nrepl.middleware.util.namespace :as n]))

(deftest test-project-namespaces
  (is (contains? (into #{} (n/project-namespaces))
                 'cider.nrepl.middleware.util.namespace)))

(deftest test-loaded-namespaces
  ;; If we don't pass the second arg, some cider ns will be returned
  (is (some #(re-find #".*nrepl" %) (n/loaded-namespaces)))
  ;; Shouldn't return any cider.nrepl namespaces
  (is (not-any? #(re-find #".*nrepl" %)
                (n/loaded-namespaces [".*nrepl"]))))
