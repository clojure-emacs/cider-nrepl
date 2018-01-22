(ns cider.nrepl.middleware.test.extensions
  "Extensions to `clojure.test` functionality.

  These are kept in a separate namespace because they are, by definition,
  opinionated."
  (:require [clojure.data :as data]
            [clojure.pprint :as pp]
            [clojure.test :as test :refer [assert-expr]]))

;; From pjstadig/humane-test-output
;; https://github.com/pjstadig/humane-test-output
(defn =-body
  [msg expected more]
  (if (seq more)
    `(let [more# (list ~@more)
           result# (apply = ~expected more#)]
       (->> (if result#
              {:type :pass}
              {:type :fail
               :diffs (->> (remove #(= ~expected %) more#)
                           (map #(vector % (data/diff ~expected %))))})
            (merge {:message ~msg,
                    :expected ~expected
                    :actual more#})
            test/do-report)
       result#)
    `(throw (Exception. "= expects more than one argument"))))

(defmethod assert-expr '= [msg [_ expected & more]]
  (=-body msg expected more))

;; In cases where an is form is part of a macro expansion assert-expr will get
;; called with the fully qualified name for = (clojure.core/=)
;; See: https://github.com/clojure-emacs/cider-nrepl/pull/478#pullrequestreview-90616379
(defmethod assert-expr 'clojure.core/= [msg [_ expected & more]]
  (=-body msg expected more))

(defn diffs-result
  "Convert diffs data to form appropriate for transport."
  [diffs]
  (let [pprint-str #(with-out-str (pp/pprint %))]
    (map (fn [[a [removed added]]]
           [(pprint-str a)
            [(pprint-str removed) (pprint-str added)]])
         diffs)))
