(ns cider.nrepl.middleware.out-test
  (:require [cider.nrepl.middleware.out :as o]
            [clojure.test :refer :all]))

(defn random-str []
  (->> #(format "%x" (rand-int 15))
       (repeatedly 10)
       (apply str)))

(def the-meta {:id (random-str)})

(defn test []
  {:id (random-str)})

(def msg {:op "eval" :id (random-str)
          :transport 90
          :some-other-key 10
          :session (atom {} :meta the-meta)})

(remove-watch o/tracked-sessions-map :update-out)

(deftest maybe-register-session
  (with-redefs [o/tracked-sessions-map (atom {})]
    (o/maybe-register-session (assoc msg :op "clone"))
    (is (= @o/tracked-sessions-map {}))
    (o/maybe-register-session msg)
    (let [{:keys [transport session id some-other-key]} (@o/tracked-sessions-map (:id the-meta))]
      (is (= transport (:transport msg)))
      (is (= session (:session msg)))
      (is (= id (:id msg)))
      (is (not some-other-key)))))
