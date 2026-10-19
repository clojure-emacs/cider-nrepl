(ns cider.test-ns.resolve-missing-ns
  "The namespace `cider/resolve-missing` resolves symbols in, in its tests."
  (:require
   [cider.test-ns.first-test-ns :as first-ns]
   [cider.test-ns.second-test-ns :refer [same-name-testing-function]])
  (:import
   (java.util Date)))

(deftype ResolvableType [])

(defrecord ResolvableRecord [])

(defn local-function [] (same-name-testing-function) (Date.))

(defn- private-function [] first-ns/some-test-var)
