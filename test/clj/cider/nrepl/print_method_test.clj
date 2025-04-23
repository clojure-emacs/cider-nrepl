(ns cider.nrepl.print-method-test
  (:require
   [cider.nrepl.print-method :refer :all]
   [clojure.test :refer :all]))

(defn dummy-fn [o])

(deftest print-atoms-test
  (is (= "#atom[\"\"]" (pr-str (atom ""))))
  (is (= "#atom[nil]" (pr-str (atom nil))))
  (is (= "#atom[{:foo :bar}]" (pr-str (atom {:foo :bar}))))
  (is (= "#atom[#function[clojure.core/+]]" (pr-str (atom +)))))

(deftest print-idrefs-test
  (let [f (future (Thread/sleep 200) 1)
        p (promise)
        d (delay 1)
        a (agent 1)]
    (are [o r] (= r (pr-str o))
      a "#agent[1]"
      d "#delay[<pending>]"
      f "#future[<pending>]"
      p "#promise[<pending>]")
    (Thread/sleep 300)
    @d
    (deliver p 1)
    @f
    (are [o r] (= r (pr-str o))
      f "#future[1]"
      d "#delay[1]"
      p "#promise[1]")))

(deftest print-functions-test
  (are [f s] (= (pr-str f) s)
    print-functions-test "#function[cider.nrepl.print-method-test/print-functions-test]"
    dummy-fn "#function[cider.nrepl.print-method-test/dummy-fn]"
    + "#function[clojure.core/+]"
    * "#function[clojure.core/*]"
    / "#function[clojure.core//]"
    fn? "#function[clojure.core/fn?]"))

(deftest print-multimethods-test
  (require 'cider.nrepl.middleware.track-state)
  (let [var (resolve 'print-method)]
    (is (re-find (re-pattern (format "#multifn\\[%s 0x[a-z0-9]+\\]"
                                     (:name (meta var))))
                 (pr-str @var)))))

(deftest print-namespaces-test
  (are [f s] (= (pr-str f) s)
    (find-ns 'clojure.core) "#namespace[clojure.core]"
    (find-ns 'cider.nrepl.print-method) "#namespace[cider.nrepl.print-method]"
    (find-ns 'clojure.test) "#namespace[clojure.test]"))
