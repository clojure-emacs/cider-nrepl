(ns cider.nrepl.middleware.test.extensions
  "Extensions to `clojure.test` functionality.

  These are kept in a separate namespace because they are, by definition,
  opinionated."
  (:require [clojure.data :as data]
            [clojure.pprint :as pp]
            [clojure.test :as test :refer [assert-expr]]))

;; From pjstadig/humane-test-output
;; https://github.com/pjstadig/humane-test-output
(defmethod assert-expr '= [msg [_ expected & more]]
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

(defn diffs-result
  "Convert diffs data to form appropriate for transport."
  [diffs]
  (let [pprint-str #(with-out-str (pp/pprint %))]
    (map (fn [[a [removed added]]]
           [(pprint-str a)
            [(pprint-str removed) (pprint-str added)]])
         diffs)))
