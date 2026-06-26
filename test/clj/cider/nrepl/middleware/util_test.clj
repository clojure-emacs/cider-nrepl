(ns cider.nrepl.middleware.util-test
  (:require
   [cider.nrepl.middleware.util :as util]
   [clojure.test :refer :all]))

(deftest msg->var-test
  (testing "resolves the var named by the msg's :ns and :sym (as strings)"
    (is (= #'clojure.core/map (util/msg->var {:ns "clojure.core" :sym "map"}))))
  (testing "coerces symbols too"
    (is (= #'clojure.core/map (util/msg->var {:ns 'clojure.core :sym 'map}))))
  (testing "returns nil when the symbol doesn't resolve"
    (is (nil? (util/msg->var {:ns "clojure.core" :sym "definitely-not-a-var"})))))
