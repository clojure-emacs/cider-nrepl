(ns cider.nrepl.print-method-test
  (:require
   [cider.nrepl.print-method :refer :all]
   [clojure.test :refer :all])
  (:import
   java.util.regex.Pattern))

(use-fixtures :each (fn [t]
                      (binding [*pretty-objects* true]
                        (t))))

(defn dummy-fn [o])

(deftest print-atoms-test
  (is (re-find #"#atom\[\"\" 0x[a-z0-9]+\]" (pr-str (atom ""))))
  (is (re-find #"#atom\[nil 0x[a-z0-9]+\]" (pr-str (atom nil))))
  (is (re-find #"#atom\[\{:foo :bar\} 0x[a-z0-9]+\]" (pr-str (atom {:foo :bar}))))
  (is (re-find #"#atom\[#function\[clojure.core/\+\] 0x[a-z0-9]+\]" (pr-str (atom +)))))

(deftest print-idrefs-test
  (let [f (future (Thread/sleep 200) 1)
        p (promise)
        d (delay 1)
        a (agent 1)]
    (are [o r] (re-find r (pr-str o))
      a #"#agent\[\{:status :ready, :val 1\} 0x[a-z0-9]+\]"
      d #"#delay\[\{:status :pending, :val nil\} 0x[a-z0-9]+\]"
      f #"#future\[\{:status :pending, :val nil\} 0x[a-z0-9]+\]"
      p #"#promise\[\{:status :pending, :val nil\} 0x[a-z0-9]+\]")
    (Thread/sleep 300)
    @d
    (deliver p 1)
    @f
    (are [o r] (re-find r (pr-str o))
      f #"#future\[\{:status :ready, :val 1\} 0x[a-z0-9]+\]"
      d #"#delay\[\{:status :ready, :val 1\} 0x[a-z0-9]+\]"
      p #"#promise\[\{:status :ready, :val 1\} 0x[a-z0-9]+\]")))

(deftest print-functions-test
  (are [f s] (= (pr-str f) s)
    print-functions-test "#function[cider.nrepl.print-method-test/print-functions-test]"
    dummy-fn "#function[cider.nrepl.print-method-test/dummy-fn]"
    multifn-name "#function[cider.nrepl.print-method/multifn-name]"
    + "#function[clojure.core/+]"
    * "#function[clojure.core/*]"
    / "#function[clojure.core//]"
    fn? "#function[clojure.core/fn?]"))

(deftest print-multimethods-test
  (require 'cider.nrepl.middleware.track-state)
  (let [var (resolve 'print-method)]
    (is (re-find (Pattern/compile (format "#multifn\\[%s 0x[a-z0-9]+\\]"
                                          (:name (meta var))))
                 (pr-str @var)))))

(deftest print-namespaces-test
  (are [f s] (= (pr-str f) s)
    (find-ns 'clojure.core) "#namespace[clojure.core]"
    (find-ns 'cider.nrepl.print-method) "#namespace[cider.nrepl.print-method]"
    (find-ns 'clojure.test) "#namespace[clojure.test]"))
