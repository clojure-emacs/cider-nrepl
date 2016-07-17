(ns cider.nrepl.middleware.version-test
  (:require [cider.nrepl.middleware.version :as v]
            [cider.nrepl.test-session :as session]
            [clojure.test :refer :all]))

(deftest cider-version-test
  (let [outer-map (#'v/cider-version-reply {})
        version-map (:cider-version outer-map)]
    (is (contains? version-map :major))
    (is (contains? version-map :minor))
    (is (contains? version-map :incremental))
    (is (contains? version-map :version-string))))

(use-fixtures :once session/session-fixture)

(deftest integration-test
  (testing "cider-version op"
    (let [response (session/message {:op "cider-version"})
          version-map (:cider-version response)]
      (is (= #{"done"} (:status response)))
      (is (contains? version-map :major))
      (is (contains? version-map :minor))
      (is (contains? version-map :incremental))
      (is (contains? version-map :version-string))))

  (testing "describe op"
    (let [response (session/message {:op "describe"})
          aux-map  (:aux response)
          version-map (:cider-version aux-map)]
      (is (= #{"done"} (:status response)))
      (is (contains? version-map :major))
      (is (contains? version-map :minor))
      (is (contains? version-map :incremental))
      (is (contains? version-map :version-string)))))
