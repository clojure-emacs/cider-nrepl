(ns cider.nrepl.middleware.stacktrace-spec-test
  (:require
   [cider.nrepl.middleware.stacktrace :refer :all]
   [clojure.spec.alpha :as s]
   [cider.nrepl.pprint :refer [pprint]]
   [clojure.test :refer :all]))

(s/check-asserts true)

(defn causes
  [form]
  (analyze-causes
   (try (eval form)
        (catch Exception e
          e))
   pprint))

(defn stack-frames
  [form]
  (analyze-stacktrace
   (try (eval form)
        (catch Exception e
          e))))

(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(s/def ::email-type (s/and string? #(re-matches email-regex %)))
(s/def ::first-name string?)
(s/def ::last-name string?)
(s/def ::email ::email-type)
(s/def ::person (s/keys :req [::first-name ::last-name ::email]
                        :opt [::phone]))

(deftest spec-assert-stacktrace-test

  (def broken-musk {::first-name "Elon"
                    ::last-name "Musk"
                    ::email "n/a"})

  (def broken-musk-causes
    (causes
     `(s/assert ::person broken-musk)))

  (testing "Spec assert components"
    (is (= 1 (count broken-musk-causes)))
    (is (:stacktrace (first broken-musk-causes)))
    (is (:message (first broken-musk-causes)))
    (is (:spec (first broken-musk-causes))))

  (testing "Spec assert data components"
    (let [spec (:spec (first broken-musk-causes))]
      (is (:spec spec))
      (is (string? (:value spec)))
      (is (= 1 (count (:problems spec))))))

  (testing "Spec assert problems components"
    (let [probs (->> broken-musk-causes first :spec :problems first)]
      (is (:in probs))
      (is (:val probs))
      (is (:predicate probs))
      (is (:spec probs))
      (is (:at probs)))))
