(ns cider.nrepl.middleware.log-test
  (:require [cider.nrepl.test-session :as session]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [logjam.framework :as framework]))

(use-fixtures :each session/session-fixture)

(def appender
  {:id "my-appender"})

(defn frameworks []
  (vals (framework/resolve-frameworks)))

(defn- find-framework [frameworks framework]
  (some #(and (= (name (:id framework)) (:id %)) %) frameworks))

(defn- exception-gen []
  (->> (gen/tuple gen/string-alphanumeric
                  (gen/map gen/keyword gen/any-printable-equatable))
       (gen/fmap (fn [[msg data]] (ex-info msg data)))))

(defn event-gen [framework]
  (->> (gen/tuple (gen/hash-map
                   :arguments (gen/vector gen/any-printable-equatable)
                   :logger gen/string-ascii
                   :message gen/string-ascii
                   :thread gen/string-ascii
                   :timestamp gen/nat)
                  (gen/elements (:levels framework))
                  (exception-gen))
       (gen/fmap (fn [[event level exception]]
                   (cond-> (assoc event :level (:name level))
                     (= :error (:category level))
                     (assoc :exception exception))))))

(defn- uuid-str? [s]
  (try (java.util.UUID/fromString s)
       (catch Exception _)))

(defn- add-appender [framework appender & [opts]]
  (let [{:keys [status cider/log-add-appender] :as response}
        (session/message (merge {:op "cider/log-add-appender"
                                 :framework (:id framework)
                                 :appender (:id appender)}
                                opts))]
    (assert (= #{"done"} status) response)
    log-add-appender))

(defn- add-consumer [framework appender & [opts]]
  (let [{:keys [status cider/log-add-consumer] :as response}
        (session/message (merge {:op "cider/log-add-consumer"
                                 :framework (:id framework)
                                 :appender (:id appender)}
                                opts))]
    (assert (= #{"done"} status) response)
    log-add-consumer))

(defn- remove-appender [framework appender]
  (let [{:keys [status cider/log-remove-appender] :as response}
        (session/message {:op "cider/log-remove-appender"
                          :framework (:id framework)
                          :appender (:id appender)})]
    (assert (= #{"done"} status) response)
    log-remove-appender))

(defn- search-events [framework appender & [opts]]
  (let [{:keys [status cider/log-search] :as response}
        (session/message (merge {:op "cider/log-search"
                                 :framework (:id framework)
                                 :appender (:id appender)}
                                opts))]
    (assert (= #{"done"} status) response)
    log-search))

(defmacro with-each-framework
  "Evaluate `body` for each `framework` bound to `framework-sym`."
  {:style/indent 1}
  [[framework-sym frameworks] & body]
  `(doseq [framework# ~frameworks :let [~framework-sym framework#]]
     (testing (format "Log framework %s" (:name framework#))
       ~@body)))

(defmacro with-appender
  "Add an appender for `framework`, evaluate `body` and remove the appender."
  {:style/indent 1}
  [[framework appender options] & body]
  `(let [framework# ~framework, appender# ~appender]
     (add-appender framework# appender# ~options)
     (try ~@body (finally (remove-appender framework# appender#)))))

(deftest test-add-appender
  (with-each-framework [framework (frameworks)]
    (let [options {:filters {} :size 10 :threshold 10}
          appender' (add-appender framework appender options)]
      (is (= [] (:consumers appender')))
      (is (= (:filters options) (:filters appender')))
      (is (= (:id appender) (:id appender')))
      (is (= (:root-logger framework) (:logger appender')))
      (is (= (:size options) (:size appender')))
      (is (= (:threshold options) (:threshold appender')))
      (doseq [key [:filters :id :size :threshold]]
        (is (get appender' key))))
    (remove-appender framework appender)))

(deftest test-add-consumer
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (let [options {:filters {:level :INFO}}
            consumer (add-consumer framework appender options)]
        (is (uuid-str? (:id consumer)))
        (is (= {:level "INFO"} (:filters consumer))))
      (framework/log framework {:message "a-1"})
      ;; TODO: How to receive the async log event?
      )))

(deftest test-analyze-stacktrace
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:message "a-1" :exception (ex-info "BOOM" {:some (Object.)})})
      (let [events (search-events framework appender {})]
        (is (= 1 (count events)))
        (let [event (first events)]
          (is (uuid-str? (:id event)))
          (is (string? (:level event)))
          (is (string? (:logger event)))
          (is (= "a-1" (:message event)))
          (is (int? (:timestamp event)))
          (let [response (session/message {:op "cider/log-analyze-stacktrace"
                                           :framework (:id framework)
                                           :appender (:id appender)
                                           :event (:id event)})]
            (is (= #{"done"} (:status response)))
            (is (every? #(set/subset? #{:type :flags} (set (keys %)))
                        (:stacktrace response)))))))))

(deftest test-clear
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:message "a-1"})
      (let [response (session/message {:op "cider/log-clear-appender"
                                       :framework (:id framework)
                                       :appender (:id appender)})]
        (is (= #{"done"} (:status response)))
        (is (= {:consumers []
                :filters {}
                :id (:id appender)
                :logger (:root-logger framework)
                :size 100000
                :threshold 10}
               (:cider/log-clear-appender response)))))))

(deftest test-exceptions
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:message "a-1" :exception (IllegalArgumentException. "BOOM")})
      (framework/log framework {:message "b-1" :exception (IllegalStateException. "BOOM")})
      (framework/log framework {:message "b-2" :exception (IllegalStateException. "BOOM")})
      (let [response (session/message {:op "cider/log-exceptions"
                                       :framework (:id framework)
                                       :appender (:id appender)})]
        (is (= #{"done"} (:status response)))
        (is (= {:java.lang.IllegalArgumentException 1
                :java.lang.IllegalStateException 2}
               (:cider/log-exceptions response)))))))

(deftest test-frameworks
  (let [{:keys [cider/log-frameworks status]} (session/message {:op "cider/log-frameworks"})]
    (is (= #{"done"} status))
    (is (= ["Logback" "Java Util Logging"] (map :name log-frameworks)))
    (with-each-framework [framework (frameworks)]
      (let [framework' (find-framework log-frameworks framework)]
        (is (= [] (:appenders framework')))
        (is (= (:id framework) (name (:id framework'))))
        (is (= (:javadoc-url framework) (:javadoc-url framework')))
        (is (= (:name framework) (:name framework')))
        (is (= (map (fn [level]
                      {:name (name (:name level))
                       :category (name (:category level))
                       :weight (:weight level)})
                    (:levels framework))
               (:levels framework')))
        (is (= (:website-url framework) (:website-url framework')))
        (is (string? (:root-logger framework')))))))

(deftest test-frameworks-add-appender
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (let [{:keys [cider/log-frameworks status]} (session/message {:op "cider/log-frameworks"})]
        (is (= #{"done"} status))
        (let [framework' (find-framework log-frameworks framework)]
          (is (= [{:consumers []
                   :filters {}
                   :id (:id appender)
                   :logger (:root-logger framework)
                   :size 100000
                   :threshold 10}]
                 (:appenders framework')))
          (is (= (:id framework) (name (:id framework'))))
          (is (= (:javadoc-url framework) (:javadoc-url framework')))
          (is (= (:name framework) (:name framework')))
          (is (= (:website-url framework) (:website-url framework')))
          (is (string? (:root-logger framework'))))))))

(deftest test-format-event
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (add-appender framework appender)
      (framework/log framework {:message "a-1"})
      (framework/log framework {:message "a-2"})
      (doseq [event (:cider/log-search
                     (session/message
                      {:op "cider/log-search"
                       :framework (:id framework)
                       :appender (:id appender)}))]
        (let [response (session/message {:op "cider/log-format-event"
                                         :framework (:id framework)
                                         :appender (:id appender)
                                         :event (:id event)})]
          (is (= #{"done"} (:status response)))
          (is (re-matches (re-pattern (str ".*" (:message event) ".*"))
                          (:cider/log-format-event response))))))))

(deftest test-inspect
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (add-appender framework appender)
      (framework/log framework {:message "a-1"})
      (framework/log framework {:message "a-2"})
      (doseq [event (:cider/log-search
                     (session/message
                      {:op "cider/log-search"
                       :framework (:id framework)
                       :appender (:id appender)}))]
        (let [response (session/message {:op "cider/log-inspect-event"
                                         :framework (:id framework)
                                         :appender (:id appender)
                                         :event (:id event)})]
          (is (= #{"done"} (:status response)))
          (is (re-matches (re-pattern (format "(?s).*%s.*" (:id event)))
                          (first (:value response)))))))))

(deftest test-levels
  (with-each-framework [framework (frameworks)]
    (let [levels (map :name (reverse (:levels framework)))]
      (with-appender [framework appender]
        (doseq [level levels]
          (framework/log framework {:level level :message (name level)}))
        (let [response (session/message {:op "cider/log-levels"
                                         :framework (:id framework)
                                         :appender (:id appender)})]
          (is (= #{"done"} (:status response)))
          (is (= (into {} (map #(vector % 1) levels))
                 (:cider/log-levels response))))))))

(deftest test-loggers
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:logger "LOGGER-A" :message "a-1"})
      (framework/log framework {:logger "LOGGER-B" :message "b-1"})
      (framework/log framework {:logger "LOGGER-B" :message "b-2"})
      (let [response (session/message {:op "cider/log-loggers"
                                       :framework (:id framework)
                                       :appender (:id appender)})]
        (is (= #{"done"} (:status response)))
        (is (= {:LOGGER-A 1 :LOGGER-B 2} (:cider/log-loggers response)))))))

(deftest test-search
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:message "a-1"})
      (framework/log framework {:message "a-2"})
      (framework/log framework {:message "a-3"})
      (let [events (search-events framework appender {})]
        (is (= 3 (count events)))
        (is (= ["a-3" "a-2" "a-1"] (map :message events)))))))

(deftest test-search-by-level
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (let [[level-1 level-2 level-3] (map :name (:levels framework))]
        (framework/log framework {:level level-1 :message "a-1"})
        (framework/log framework {:level level-2 :message "a-2"})
        (framework/log framework {:level level-3 :message "a-3"})
        (is (= 3 (count (search-events framework appender {:filters {:level level-1}}))))
        (is (= 2 (count (search-events framework appender {:filters {:level level-2}}))))
        (is (= 1 (count (search-events framework appender {:filters {:level level-3}}))))))))

(deftest test-search-by-exception
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:message "a-1"})
      (framework/log framework {:message "a-2" :exception (IllegalArgumentException. "BOOM")})
      (framework/log framework {:message "a-3" :exception (IllegalStateException. "BOOM")})
      (let [options {:filters {:exceptions ["java.lang.IllegalStateException"]}}
            events (search-events framework appender options)]
        (is (= 1 (count events)))
        (let [event (first events)]
          (is (uuid-str? (:id event)))
          (is (string? (:level event)))
          (is (string? (:logger event)))
          (is (= "a-3" (:message event)))
          (is (int? (:timestamp event))))))))

(deftest test-search-by-pattern
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:message "a-1"})
      (framework/log framework {:message "a-2"})
      (framework/log framework {:message "a-3"})
      (let [events (search-events framework appender {:filters {:pattern "a-3"}})]
        (is (= 1 (count events)))
        (let [event (first events)]
          (is (uuid-str? (:id event)))
          (is (= "INFO" (:level event)))
          (is (string? (:logger event)))
          (is (= "a-3" (:message event)))
          (is (int? (:timestamp event))))))))

(deftest test-search-by-start-and-end-time
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:message "a-1"})
      (Thread/sleep 100)
      (framework/log framework {:message "a-2"})
      (Thread/sleep 100)
      (framework/log framework {:message "a-3"})
      (let [[event-3 event-2 event-1] (search-events framework appender {})]
        (let [options {:filters {:start-time (inc (:timestamp event-1))
                                 :end-time (dec (:timestamp event-3))}}
              events (search-events framework appender options)]
          (is (= 1 (count events)))
          (let [event (first events)]
            (is (= (:id event-2) (:id event)))
            (is (= "INFO" (:level event)))
            (is (string? (:logger event)))
            (is (= "a-2" (:message event)))
            (is (int? (:timestamp event)))))))))

(deftest test-threads
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (framework/log framework {:message "a-1"})
      (let [response (session/message
                      {:op "cider/log-threads"
                       :framework (:id framework)
                       :appender (:id appender)})]
        (is (= #{"done"} (:status response)))
        (let [threads (:cider/log-threads response)]
          (is (seq threads))
          (doseq [[thread frequency] threads]
            (is (keyword? thread))
            (is (pos-int? frequency))))))))

(deftest test-remove-appender
  (with-each-framework [framework (frameworks)]
    (testing "remove unregistered appender"
      (let [response (session/message
                      {:op "cider/log-remove-appender"
                       :framework (:id framework)
                       :appender "unknown"})]
        (is (= #{"cider/log-remove-appender-error" "done"} (:status response)))))
    (testing "remove registered appender"
      (let [appender (add-appender framework appender)
            appender' (remove-appender framework appender)]
        (is (= (:id appender) (doto (:id appender') (assert "Appender not found"))))
        (let [{:keys [log-frameworks status]} (session/message {:op "cider/log-frameworks"})]
          (is (= #{"done"} status))
          (is (empty? (:appenders (find-framework log-frameworks framework)))))))))

(deftest test-remove-consumer
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (let [consumer (add-consumer framework appender {:filters {:level :INFO}})
            response (session/message
                      {:op "cider/log-remove-consumer"
                       :framework (:id framework)
                       :appender (:id appender)
                       :consumer (:id consumer)})]
        (is (= #{"done"} (:status response)))
        (is (= {:id (:id consumer)}
               (:cider/log-remove-consumer response)))
        (let [{:keys [cider/log-frameworks status]} (session/message {:op "cider/log-frameworks"})]
          (is (= #{"done"} status))
          (is (= [{:consumers []
                   :filters {}
                   :id (:id appender)
                   :logger (:root-logger framework)
                   :size 100000
                   :threshold 10}]
                 (:appenders (find-framework log-frameworks framework)))))))))

(deftest test-update-appender
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (let [response (session/message
                      {:op "cider/log-update-appender"
                       :appender (:id appender)
                       :filters {:pattern "A.*"}
                       :framework (:id framework)
                       :size 2
                       :threshold 0})]
        (is (= #{"done"} (:status response)))
        (let [appender (:cider/log-update-appender response)]
          (is (= {:pattern "A.*"} (:filters appender)))
          (is (= 2 (:size appender)))
          (is (= 0 (:threshold appender)))
          (framework/log framework {:message "A1"})
          (framework/log framework {:message "A2"})
          (framework/log framework {:message "A3"})
          (framework/log framework {:message "A4"})
          (framework/log framework {:message "B1"})
          (let [events (:cider/log-search
                        (session/message
                         {:op "cider/log-search"
                          :framework (:id framework)
                          :appender (:id appender)}))]
            (is (= ["A4" "A3"] (map :message events)))))))))

(deftest test-update-consumer
  (with-each-framework [framework (frameworks)]
    (with-appender [framework appender]
      (let [consumer (add-consumer framework appender {:filters {:level :INFO}})
            response (session/message
                      {:op "cider/log-update-consumer"
                       :framework (:id framework)
                       :appender (:id appender)
                       :consumer (:id consumer)
                       :filters {:level :DEBUG}})]
        (is (= #{"done"} (:status response)))
        (is (= {:id (:id consumer)
                :filters {:level "DEBUG"}}
               (:cider/log-update-consumer response)))))))

(defn log-something [framework & [n sleep]]
  (doseq [event (gen/sample (event-gen framework) (or n 1))]
    (framework/log framework event)
    (Thread/sleep (or sleep 10))))

(deftest test-log-something
  (doseq [framework (frameworks)]
    (is (nil? (log-something framework 10)))))

(comment
  (future (log-something (first (frameworks)) 1000)))
