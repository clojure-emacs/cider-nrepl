(ns cider.nrepl.middleware.out-test
  (:require
   [cider.nrepl.middleware.out :as o]
   [clojure.test :refer :all]))

(defn random-str []
  (->> #(format "%x" (rand-int 15))
       (repeatedly 10)
       (apply str)))

(def the-meta {:id (random-str)})

(def msg {:op "eval" :id (random-str)
          :transport 90
          :some-other-key 10
          :session (atom {} :meta the-meta)})

(remove-watch o/tracked-sessions-map :update-out)

(deftest maybe-register-session-test
  (with-redefs [o/tracked-sessions-map (atom {})]
    (o/subscribe-session msg)
    (let [{:keys [transport session id some-other-key]} (@o/tracked-sessions-map (:id the-meta))]
      (is (= transport (:transport msg)))
      (is (= session (:session msg)))
      (is (= id (:id msg)))
      (is (not some-other-key)))
    (o/unsubscribe-session (:id the-meta))
    (is (empty? @o/tracked-sessions-map))))

(deftest original-output-test
  (testing "The mapping is computed once; not doing so would defeat is point and create issues."
    (is (map? o/original-output))
    (is (not (fn? o/original-output)))))
