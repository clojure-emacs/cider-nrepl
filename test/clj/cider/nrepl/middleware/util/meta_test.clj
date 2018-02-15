(ns cider.nrepl.middleware.util.meta-test
  (:require [cider.nrepl.middleware.util.meta :as um]
            [clojure.repl :as repl]
            [clojure.test :refer :all]))

(defn- test-fn "docstring"
  ([a b] nil)
  ([a] nil)
  ([]))

(deftest dummy-test)

(deftest relevant-meta-test
  (is (= (um/relevant-meta (meta #'test-fn))
         {:arglists "([a b] [a] [])"
          :doc "\"docstring\""}))
  (is (= (:macro (um/relevant-meta (meta #'deftest)))
         "true"))
  (let [m (meta #'dummy-test)]
    ;; #'strip-meta-test refers to the deftest, and not the defn
    (alter-meta! #'dummy-test merge {:indent 1 :cider-instrumented 2 :something-else 3})
    (is (= (um/relevant-meta (meta #'dummy-test))
           {:indent "1", :test (pr-str (:test (meta #'dummy-test)))}))
    (alter-meta! #'dummy-test (fn [x y] y) m)))
