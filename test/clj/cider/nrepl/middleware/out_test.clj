(ns cider.nrepl.middleware.out-test
  (:require
   [cider.nrepl.middleware.out :as o]
   [clojure.test :refer :all])
  (:import
   [java.io PrintWriter StringWriter]))

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

(defmacro with-original-output
  [[m] & body]
  `(let [orig# o/original-output]
     (try
       (alter-var-root #'o/original-output (constantly ~m))
       ~@body
       (finally
         (alter-var-root #'o/original-output (constantly orig#))))))

(defn- forking-printer-test-streams
  []
  (let [out-writer (StringWriter.)
        message-writers [(StringWriter.) (StringWriter.)]
        messages         [{:session (atom {#'*out* (message-writers 0)})}
                          {:session (atom {#'*err* (message-writers 1)})}]
        printers [(o/forking-printer [(messages 0) (messages 1)] :out)
                  (o/forking-printer [(messages 0) (messages 1)] :err)]]
    {:out-writer out-writer
     :message-writers message-writers
     :printers printers}))

(deftest forking-printer-test
  (testing "forking-printer prints to all message streams and original stream"
    (testing "with String argument "
      (let [{:keys [^StringWriter out-writer
                    message-writers
                    printers]}
            (forking-printer-test-streams)]
        (with-original-output [{:out out-writer}]
          (.write ^PrintWriter (printers 0) "Hello")
          (is (= "Hello" (.toString out-writer)))
          (is (= "Hello" (.toString ^StringWriter (message-writers 0))))
          (is (= "" (.toString ^StringWriter (message-writers 1)))))
        (with-original-output [{:err out-writer}]
          (.write ^PrintWriter (printers 1) "Hello")
          (is (= "HelloHello" (.toString out-writer)))
          (is (= "Hello" (.toString ^StringWriter (message-writers 0))))
          (is (= "Hello" (.toString ^StringWriter (message-writers 1)))))))
    (testing "with int"
      (let [{:keys [^StringWriter out-writer
                    message-writers
                    printers]}
            (forking-printer-test-streams)
            an-int (int 32)]
        (with-original-output [{:out out-writer}]
          (.write ^PrintWriter (printers 0) an-int)
          (is (= " " (.toString out-writer)))
          (is (= " " (.toString ^StringWriter (message-writers 0))))
          (is (= "" (.toString ^StringWriter (message-writers 1)))))
        (with-original-output [{:err out-writer}]
          (.write ^PrintWriter (printers 1) an-int)
          (is (= "  " (.toString out-writer)))
          (is (= " " (.toString ^StringWriter (message-writers 0))))
          (is (= " " (.toString ^StringWriter (message-writers 1)))))))
    (testing "with char array"
      (let [{:keys [^StringWriter out-writer
                    message-writers
                    printers]}
            (forking-printer-test-streams)]
        (with-original-output [{:out out-writer}]
          (.write ^PrintWriter (printers 0) (char-array "and"))
          (is (= "and" (.toString out-writer)))
          (is (= "and" (.toString ^StringWriter (message-writers 0))))
          (is (= "" (.toString ^StringWriter (message-writers 1)))))
        (with-original-output [{:err out-writer}]
          (.write ^PrintWriter (printers 1) (char-array "and"))
          (is (= "andand" (.toString out-writer)))
          (is (= "and" (.toString ^StringWriter (message-writers 0))))
          (is (= "and" (.toString ^StringWriter (message-writers 1)))))))
    (testing "with String with offsets"
      (let [{:keys [^StringWriter out-writer
                    message-writers
                    printers]}
            (forking-printer-test-streams)]
        (with-original-output [{:out out-writer}]
          (.write ^PrintWriter (printers 0) "12 good34" 3 4)
          (is (= "good" (.toString out-writer)))
          (is (= "good" (.toString ^StringWriter (message-writers 0))))
          (is (= "" (.toString ^StringWriter (message-writers 1)))))
        (with-original-output [{:err out-writer}]
          (.write ^PrintWriter (printers 1) "12 good34" 3 4)
          (is (= "goodgood" (.toString out-writer)))
          (is (= "good" (.toString ^StringWriter (message-writers 0))))
          (is (= "good" (.toString ^StringWriter (message-writers 1)))))))
    (testing "with char array with offsets"
      (let [{:keys [^StringWriter out-writer
                    message-writers
                    printers]}
            (forking-printer-test-streams)]
        (with-original-output [{:out out-writer}]
          (.write ^PrintWriter (printers 0) (char-array " bye67") 1 3)
          (is (= "bye" (.toString out-writer)))
          (is (= "bye" (.toString ^StringWriter (message-writers 0))))
          (is (= "" (.toString ^StringWriter (message-writers 1)))))
        (with-original-output [{:err out-writer}]
          (.write ^PrintWriter (printers 1) (char-array " bye67") 1 3)
          (is (= "byebye" (.toString out-writer)))
          (is (= "bye" (.toString ^StringWriter (message-writers 0))))
          (is (= "bye" (.toString ^StringWriter (message-writers 1)))))))))

(defn find-thread [thread-name]
  (->> (Thread/getAllStackTraces)
       (.keySet)
       (filter #(= thread-name (.getName ^Thread %)))
       first))

(deftest print-stream-flush-test
  (let [out-writer (StringWriter.)
        printer (o/print-stream (volatile! out-writer))]
    (.write printer 32)
    (loop [i 1000]
      (when (and (= "" (.toString out-writer)) (>= 0 i))
        (Thread/sleep 1)
        (recur (unchecked-dec i))))
    (is (find-thread "cider-nrepl output flusher 1"))
    (is (= " " (.toString out-writer)))))
