(ns cider.nrepl.middleware.test.diff
  (:require
   [clojure.data :as data]
   [clojure.pprint :as pprint]
   [lambdaisland.deep-diff :as deep-diff]))

(defmulti diff (fn [m _a _b] (:diff-backend m)))

(defmulti pprint (fn [m _actual _diff] (:diff-backend m)))

(defmethod diff :default [m a b]
  (diff (assoc m :diff-backend :data-diff) a b))

(defmethod pprint :default [m actual diff]
  (pprint (assoc m :diff-backend :data-diff) actual diff))

(defmethod diff :deep-diff [_ a b]
  (deep-diff/diff a b))

(defmethod pprint :deep-diff [_ actual diff]
  (let [pprint #(with-out-str (deep-diff/pretty-print %))]
    [(pprint actual) (pprint diff)]))

(defmethod diff :data-diff [_ a b]
  (data/diff a b))

(defmethod pprint :data-diff [_ actual [removed added]]
  (let [pprint #(with-out-str (pprint/pprint %))]
    [(pprint actual) [(pprint removed) (pprint added)]]))
