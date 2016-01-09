(ns cider.nrepl.print-method-test
  (:require [cider.nrepl.print-method :refer :all]
            [clojure.test :refer :all])
  (:import java.util.regex.Pattern))

(defn dummy-fn [o])

(deftest print-atoms
  (is (re-find #"#atom\[ 0x[a-z0-9]+\]" (pr-str (atom ""))))
  (is (re-find #"#atom\[nil 0x[a-z0-9]+\]" (pr-str (atom nil))))
  (is (re-find #"#atom\[\{:foo :bar\} 0x[a-z0-9]+\]" (pr-str (atom {:foo :bar}))))
  (is (re-find #"#atom\[#function\[clojure.core/\+\] 0x[a-z0-9]+\]" (pr-str (atom +)))))

(deftest print-functions
  (are [f s] (= (pr-str f) s)
    print-functions "#function[cider.nrepl.print-method-test/print-functions]"
    dummy-fn "#function[cider.nrepl.print-method-test/dummy-fn]"
    multifn-name "#function[cider.nrepl.print-method/multifn-name]"
    + "#function[clojure.core/+]"
    * "#function[clojure.core/*]"
    / "#function[clojure.core//]"
    fn? "#function[clojure.core/fn?]"))

(deftest print-multimethods
  (require 'cider.nrepl.middleware.track-state)
  (let [var (resolve 'print-method)]
    (is (re-find (Pattern/compile (format "#multifn\\[%s 0x[a-z0-9]+\\]"
                                          (:name (meta var))))
                 (pr-str @var)))))

(deftest print-namespaces
  (are [f s] (= (pr-str f) s)
    (find-ns 'clojure.core) "#namespace[clojure.core]"
    (find-ns 'cider.nrepl.print-method) "#namespace[cider.nrepl.print-method]"
    (find-ns 'clojure.test) "#namespace[clojure.test]"))
