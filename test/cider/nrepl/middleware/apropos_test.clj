(ns cider.nrepl.middleware.apropos-test
  (:require [cider.nrepl.middleware.apropos :refer :all]
            [clojure.test :refer :all]))

(deftest test-namespaces
  (let [ns (-> *ns* ns-name str)]
    (testing "Namespace sort order"
      (is (= (-> (namespaces ns nil) first ns-name str)
             ns)
          "Current namespace should be first.")
      (is (-> (namespaces nil nil) first ns-name str
              (.startsWith "clojure."))
          "Absent a current namespace, clojure.* should be first."))

    (testing "Searched namespace"
      (is (= (namespaces ns ns)
             (namespaces nil ns)
             (list (find-ns (symbol ns))))
          "Should return a list containing only the searched ns."))))

(deftest test-search
  (testing "Search results"
    (is (empty? (find-symbols nil "xxxxxxxx" nil false false false))
        "Failing searches should return empty.")
    (is (= 1 (count (find-symbols nil "handle-apropos" nil false false false)))
        "Search for specific fn should return it."))

  (testing "Symbol vs docstring search"
    ;; Search for the same fn by name and docstring
    (let [x (first (find-symbols nil "handle-apropos" nil false false false))
          y (first (find-symbols nil "Return a sequence of vars whose name matches"
                                 nil true false false))]
      (is (= (dissoc x :doc)
             (dissoc y :doc))
          "Other than docstring, returned attributes should be the same.")
      (is (< (count (:doc x))
             (count (:doc y)))
          "Symbol search should return an abbreviated docstring.")
      (is (= (take 20 (:doc x))
             (take 20 (:doc y)))
          "The abbreviated docstring should be the start of the full docstring."))))
