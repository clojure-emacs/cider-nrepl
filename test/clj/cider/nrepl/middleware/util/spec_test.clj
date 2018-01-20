(ns cider.nrepl.middleware.util.spec-test
  (:require [cider.nrepl.middleware.util.spec :as cider-spec]
            [clojure.test :refer :all]))

(deftest normalize-spec-form-test
  (testing "All fn* subforms should be normalized"
    (is (= (cider-spec/normalize-spec-form '(clojure.spec.alpha/fspec
                                             :args (clojure.spec.alpha/and
                                                    (fn* [p1__22097#]
                                                         (clojure.core/< (:start p1__22097#) (:end p1__22097#)))
                                                    (clojure.core/fn [%]
                                                      (clojure.core/< (:start %) (:end %))))
                                             :ret (fn* [p2__33098#]
                                                       (clojure.core/> (:start p2__33098#) (:end p2__33098#)))
                                             :fn nil))
           '(clojure.spec.alpha/fspec
             :args (clojure.spec.alpha/and
                    (clojure.core/fn [%] (clojure.core/< (:start %) (:end %)))
                    (clojure.core/fn [%] (clojure.core/< (:start %) (:end %))))
             :ret (clojure.core/fn [%] (clojure.core/> (:start %) (:end %)))
             :fn nil)))))
