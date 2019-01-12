(ns cider.nrepl.middleware.util.meta-test
  (:require
   [cider.nrepl.middleware.util.meta :as um]
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
  (let [the-meta (meta #'dummy-test)]
    (is (= (um/relevant-meta (merge the-meta {:indent 1
                                              :cider-instrumented 2
                                              :something-else 3}))
           {:indent "1"
            :test (pr-str (:test the-meta))}))))
