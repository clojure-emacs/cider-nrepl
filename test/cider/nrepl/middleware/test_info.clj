(ns cider.nrepl.middleware.test-info
  (:require [clojure.test :refer :all]
            [cider.nrepl.middleware.info :as info]))

(deftest test-info
  (is (info/info-clj 'cider.nrepl.middleware.info 'io))

  (is (info/info-clj 'cider.nrepl.middleware.info 'info-clj))
  
  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core)))
  
  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core/+)))
)

