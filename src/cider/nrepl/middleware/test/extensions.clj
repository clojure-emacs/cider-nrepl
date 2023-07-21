(ns cider.nrepl.middleware.test.extensions
  "Extensions to `clojure.test` functionality.

  These are kept in a separate namespace because they are, by definition,
  opinionated."
  (:require
   [clojure.data :as data]
   [clojure.pprint :as pp]
   [clojure.test :as test :refer [assert-expr]]))

(defn- diffable-objects?
  "Is a diff between `a` and `b` worth displaying to the user?

  Typically, two different scalar values don't have a useful diff.

  Also, collections of different types are not meaningfully diffable."
  [a b]
  (and (or (and (map? a)
                (map? b))

           (and (sequential? a)
                (sequential? b))

           (and (set? a)
                (set? b)))
       (not= a b)))

(defn maybe-assoc-diffs
  "Computes and assocs data diffs when the at least expected/actual pair is deemed worth diffing."
  [m expected actual]
  (let [diffable? (volatile! false)
        diffs (mapv (fn [actual-value]
                      (when (diffable-objects? expected actual-value)
                        (vreset! diffable? true))
                      [actual-value (data/diff expected actual-value)])
                    actual)]
    (cond-> m
      ;; If at least one actual value is diffable, then show diffs for all actual values,
      ;; even if some of those diffs may not be so useful.
      ;; Else one the client side could easily get confused.
      @diffable?
      (assoc :diffs diffs))))

;; From pjstadig/humane-test-output
;; https://github.com/pjstadig/humane-test-output
(defn =-body
  [msg expected more]
  (if (seq more)
    `(let [expected# ~expected
           more# (list ~@more)
           result# (apply = expected# more#)]
       (->> (if result#
              {:type :pass}
              (maybe-assoc-diffs {:type :fail}
                                 expected#
                                 (remove #(= expected# %) more#)))
            (merge {:message ~msg
                    :expected expected#
                    :actual
                    (if (= 1 (count more#))
                      ;; most times,` more` has a count of 1. For this case, we unwrap `more`,
                      ;; which has been the traditional behavior of this feature:
                      (first more#)
                      ;; if `more` has 2+ arguments, our :actual will closely resemble clojure.test's own:
                      (list ~''not (apply list ~(list 'quote '=) expected# more#)))})
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
