(ns cider.nrepl.middleware.test-info
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cider.nrepl.middleware.info :as info]))


(deftest test-resource-path
  (is (= (class (info/resource-path (subs (str (clojure.java.io/resource "clojure/core.clj")) 4)))
         java.net.URL))
  (is (= (class (info/resource-path "clojure/core.clj"))
         java.net.URL))
  (is (= (class (info/resource-path "clojure-1.5.1.jar:clojure/core.clj"))
         java.net.URL))
  (is (= (class (info/resource-path "test/cider/nrepl/middleware/test_info.clj"))
         java.io.File)))

(deftest test-info
  (is (info/info-clj 'cider.nrepl.middleware.info 'io))

  (is (info/info-clj 'cider.nrepl.middleware.info 'info-clj))
  
  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core)))
  
  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core/+)))
  ;; used to crash, sym is parsed as a class name
  (is (nil? (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'notincanter.core))))
  ;; unfound nses should fall through
  (is (nil? (info/format-response (info/info-clj 'cider.nrepl.middleware.nonexistent-namespace 'a-var))))
)

(deftest test-response
  (is (= (dissoc (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'assoc)) :file)
         '{:arglists (("map" "key" "val") ("map" "key" "val" "&" "kvs")), :ns "clojure.core", :name "assoc",
           :arglists-str "([map key val] [map key val & kvs])",
           :column 1,
           :added "1.0",
           :static "true",
           :doc "assoc[iate]. When applied to a map, returns a new map of the\n    same (hashed/sorted) type, that contains the mapping of key(s) to\n    val(s). When applied to a vector, returns a new vector that\n    contains val at index. Note - index must be <= (count vector).",
           :line 177,
           })))

