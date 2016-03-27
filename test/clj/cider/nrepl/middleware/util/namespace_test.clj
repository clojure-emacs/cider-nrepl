(ns cider.nrepl.middleware.util.namespace-test
  (:require [clojure.test :refer :all])
  (:require [cider.nrepl.middleware.util.namespace :as n]))

(deftest test-project-namespaces
  (is (contains? (into #{} (n/project-namespaces))
                 'cider.nrepl.middleware.util.namespace)))
