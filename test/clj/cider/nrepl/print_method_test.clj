(ns cider.nrepl.print-method-test
  (:require [cider.nrepl.print-method :refer :all]
            [clojure.test :refer :all])
  (:import java.util.regex.Pattern))

(defn dummy-fn [o])

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
  (doseq [it '(print-method cider.nrepl.middleware.track-state/ns-as-map)]
    (let [var (resolve it)]
      (is (re-find (Pattern/compile (format "#multifn\\[%s 0x[a-z0-9]+\\]"
                                            (:name (meta var))))
                   (pr-str @var))))))

(deftest print-namespaces
  (are [f s] (= (pr-str f) s)
    *ns* "#namespace[cider.nrepl.print-method-test]"
    (find-ns 'clojure.core) "#namespace[clojure.core]"
    (find-ns 'cider.nrepl.print-method) "#namespace[cider.nrepl.print-method]"
    (find-ns 'clojure.test) "#namespace[clojure.test]"))
