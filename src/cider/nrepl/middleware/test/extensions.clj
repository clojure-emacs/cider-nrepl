(ns cider.nrepl.middleware.test.extensions
  "Extensions to `clojure.test` functionality.

  These are kept in a separate namespace because they are, by definition,
  opinionated."
  (:require
   [cider.nrepl.middleware.test.diff :as diff]
   [clojure.test :as test :refer [assert-expr]]))

(if (find-ns 'clojure.tools.nrepl)
  (require
   '[clojure.tools.nrepl.middleware.interruptible-eval :as ie])
  (require
   '[nrepl.middleware.interruptible-eval :as ie]))

;; From pjstadig/humane-test-output
;; https://github.com/pjstadig/humane-test-output
(defn =-body
  [msg expected more]
  (if (seq more)
    (do
      `(let [more#     (list ~@more)
             expected# ~expected
             result#   (apply = expected# more#)]
         (->> (if result#
                {:type :pass}
                {:type  :fail
                 :diffs (->> more#
                             (remove #(= expected# %))
                             (map #(vector % (diff/diff ie/*msg* expected# %))))})
              (merge {:message  ~msg
                      :expected expected#
                      :actual   more#})
              test/do-report)
         result#))
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
  (map (fn [[actual diff]]
         (diff/pprint ie/*msg* actual diff))
       diffs))
