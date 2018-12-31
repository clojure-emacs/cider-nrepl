(ns cider.nrepl.middleware.test.diff
  (:require
   [clojure.data :as data]
   [clojure.pprint :as pprint]
   [lambdaisland.deep-diff :as deep-diff]))

(defmulti diff (fn [m _a _b] (:diff-backend m)))

(defmulti pprint (fn [m v] (:diff-backend m)))

(defmethod diff :default [m a b]
  (diff (assoc m :diff-backend :data-diff) a b))

(defmethod pprint :default [m v]
  (pprint (assoc m :diff-backend :data-diff) v))

(defmethod diff :deep-diff [_ a b]
  (deep-diff/diff a b))

(defmethod pprint :deep-diff [_ v]
  (with-out-str (deep-diff/pretty-print v)))

(defmethod diff :data-diff [_ a b]
  (let [[removed added] (data/diff a b)]
    [removed added]))

(defmethod pprint :data-diff [_ v]
  (with-out-str (pprint/pprint v)))
