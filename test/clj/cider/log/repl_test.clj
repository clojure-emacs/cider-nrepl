(ns cider.log.repl-test
  (:require [cider.log.framework :as framework]
            [cider.log.repl :as repl]
            [cider.log.specs]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util UUID]))

(defn frameworks []
  (vals (framework/resolve-frameworks)))

(defmacro with-each-framework
  "Evaluate `body` for each `framework` bound to `framework-sym`."
  [[framework-sym frameworks] & body]
  `(let [settings# repl/*settings*]
     (doseq [framework# ~frameworks :let [~framework-sym framework#]]
       (testing (format "Log framework %s" (:name framework#))
         (repl/set-framework! (:id framework#))
         (try ~@body
              (finally
                (repl/shutdown :framework (:id framework#))
                (alter-var-root #'repl/*settings* (constantly settings#))))))))

(deftest test-appender
  (with-each-framework [framework (frameworks)]
    (testing "without any appenders"
      (is (nil? (repl/appender))))
    (testing "with an appender"
      (repl/add-appender)
      (is (= (:appender repl/*settings*)
             (:id @(repl/appender)))))))

(deftest test-appenders
  (with-each-framework [framework (frameworks)]
    (testing "without any appenders"
      (is (empty? (repl/appenders))))
    (testing "with an appender"
      (repl/add-appender)
      (is (= [(repl/appender)] (repl/appenders))))))

(deftest test-add-appender
  (with-each-framework [framework (frameworks)]
    (is (repl/add-appender))
    (is (= (:appender repl/*settings*)
           (:id @(repl/appender))))))

(deftest test-add-consumer
  (with-each-framework [framework (frameworks)]
    (repl/add-appender)
    (is (repl/add-consumer))))

(deftest test-clear-appender
  (with-each-framework [framework (frameworks)]
    (let [level (-> framework :levels last :name)]
      (repl/add-appender)
      (repl/log :level level :message "1")
      (repl/log :level level :message "2")
      (is (= 2 (count (repl/events))))
      (repl/clear-appender)
      (is (= 0 (count (repl/events)))))))

(deftest test-event
  (with-each-framework [framework (frameworks)]
    (let [events (atom [])
          level (-> framework :levels last :name)]
      (repl/add-appender)
      (is (nil? (repl/event :event (UUID/randomUUID))))
      (repl/add-consumer :callback (fn [_ event] (swap! events conj event)))
      (repl/log :arguments [1 2 3]
                :mdc {"a" "1"}
                :level level
                :logger (:root-logger framework)
                :message "Hello World")
      (is (= 1 (count @events)))
      (let [event (first @events)]
        (is (= event (repl/event :event (:id event))))))))

(deftest test-events
  (with-each-framework [framework (frameworks)]
    (let [level (-> framework :levels last :name)]
      (repl/add-appender)
      (repl/log :level level :message "Hello World")
      (repl/log :level level :message "Hello Moon")
      (let [events  (repl/events :pattern ".*World")]
        (is (= 1 (count events)))
        (is (= "Hello World" (:message (first events))))))))

(deftest test-framework
  (testing "default framework"
    (is (map? (repl/framework))))
  (testing "unsupported framework"
    (is (nil? (repl/framework :framework "unknown")))))

(deftest test-log
  (with-each-framework [framework (frameworks)]
    (let [events (atom [])
          level (-> framework :levels last :name)]
      (repl/add-appender)
      (repl/add-consumer :callback (fn [_ event] (swap! events conj event)))
      (repl/log :arguments [1 2 3]
                :mdc {"a" "1"}
                :level level
                :logger (:root-logger framework)
                :message "Hello World")
      (is (= 1 (count @events)))
      (let [event (first @events)]
        (is (= [1 2 3] (:arguments event)))
        (is (= level (:level event)))
        (when (contains? #{"logback"} (:id framework))
          (is (= {"a" "1"} (:mdc event))))
        (is (= "Hello World" (:message event)))
        (is (pos-int? (:timestamp event)))
        (is (string? (:thread event)))))))

(deftest test-set-framework!
  (with-each-framework [framework (frameworks)]
    (doseq [framework (keys repl/*frameworks*)]
      (repl/set-framework! framework)
      (is (= framework (:id (repl/framework)))))))

(deftest test-remove-appender
  (with-each-framework [framework (frameworks)]
    (testing "without any appenders"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Log appender not registered: cider-log"
                            (repl/remove-appender))))
    (testing "with an appender"
      (repl/add-appender)
      (repl/appender)
      (repl/remove-appender)
      (is (not (repl/appender))))))

(deftest test-remove-consumer
  (with-each-framework [framework (frameworks)]
    (repl/add-appender)
    (repl/add-consumer)
    (is (repl/remove-consumer))))

(deftest test-update-appender
  (with-each-framework [framework (frameworks)]
    (repl/add-appender)
    (is (repl/update-appender))))

(deftest test-update-consumer
  (with-each-framework [framework (frameworks)]
    (repl/add-appender)
    (repl/add-consumer)
    (is (repl/update-consumer))))

(deftest test-shutdown
  (with-each-framework [framework (frameworks)]
    (testing "without any appenders"
      (is (= (:id (repl/framework))
             (:id (repl/shutdown)))))
    (testing "with an appender"
      (repl/add-appender)
      (is (= (:id (repl/framework))
             (:id (repl/shutdown))))
      (is (empty? (repl/appenders))))))
