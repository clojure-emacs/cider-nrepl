(ns cider.nrepl.middleware.test-info
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cider.nrepl.middleware.info :as info]))


(deftest test-resource-path
  (is (= (class (info/resource-path (subs (str (clojure.java.io/resource "clojure/core.clj")) 4)))
         java.net.URL))
  (is (= (class (info/resource-path "clojure/core.clj"))
         java.net.URL))
  (is (= (class (info/resource-path "test/cider/nrepl/middleware/test_info.clj"))
         java.io.File)))

(deftest test-info
  (is (info/info-clj 'cider.nrepl.middleware.info 'io))

  (is (info/info-clj 'cider.nrepl.middleware.info 'info-clj))
  
  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core)))
  
  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core/+)))
)

