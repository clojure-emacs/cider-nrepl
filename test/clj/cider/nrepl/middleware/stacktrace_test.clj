(ns cider.nrepl.middleware.stacktrace-test
  (:require
   [cider.nrepl.middleware.stacktrace :refer :all]
   [clojure.pprint :refer [pprint]]
   [clojure.test :refer :all]))

;; # Utils

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

;; ## Test fixtures

(def form1 '(throw (ex-info "oops" {:x 1} (ex-info "cause" {:y 2}))))
(def form2 '(do (defn oops [] (+ 1 "2"))
                (oops)))
(def form3 '(not-defined))
(defn divi [x y] (/ x y))
(def form4 '(divi 1 0))

(def frames1 (stack-frames form1))
(def frames2 (stack-frames form2))
(def frames4 (stack-frames form4))
(def causes1 (causes form1))
(def causes2 (causes form2))
(def causes3 (causes form3))

;; ## Tests

(deftest stacktrace-frames-test
  (testing "File types"
    ;; Should be clj and java only.
    (let [ts1 (group-by :type frames1)
          ts2 (group-by :type frames2)]
      (is (= #{:clj :java} (set (keys ts1))))
      (is (= #{:clj :java} (set (keys ts2))))))
  (testing "Full file mappings"
    (is (every?
         #(.endsWith (:file-url %) "!/clojure/core.clj")
         (filter #(= "clojure.core" (:ns %))
                 frames1)))
    (is (->> (filter #(some-> % :ns (.contains "cider")) frames1)
             (remove (comp #{"invoke" "invokeStatic"} :method)) ;; these don't have a file-url
             (every?
              #(.startsWith (:file-url %) "file:/")))))
  (testing "Clojure ns, fn, and var"
    ;; All Clojure frames should have non-nil :ns :fn and :var attributes.
    (is (every? #(every? identity ((juxt :ns :fn :var) %))
                (filter #(= :clj (:type %)) frames1)))
    (is (every? #(every? identity ((juxt :ns :fn :var) %))
                (filter #(= :clj (:type %)) frames2))))
  (testing "Clojure name demunging"
    ;; Clojure fn names should be free of munging characters.
    (is (not-any? #(re-find #"[_$]|(--\d+)" (:fn %))
                  (filter :fn frames1)))
    (is (not-any? #(re-find #"[_$]|(--\d+)" (:fn %))
                  (filter :fn frames2)))))

(deftest stacktrace-frame-flags-test
  (testing "Flags"
    (testing "for file type"
      ;; Every frame should have its file type added as a flag.
      (is (every? #(contains? (:flags %) (:type %)) frames1))
      (is (every? #(contains? (:flags %) (:type %)) frames2)))
    (testing "for tooling"
      ;; Tooling frames are classes named with 'clojure' or 'nrepl',
      ;; or are java thread runners...or calls made from these.
      (is (some #(re-find #"(clojure|nrepl|run)" (:name %))
                (filter (comp :tooling :flags) frames1)))
      (is (some #(re-find #"(clojure|nrepl|run)" (:name %))
                (filter (comp :tooling :flags) frames2))))
    (testing "for project"
      (is (not-empty (filter (comp :project :flags) frames4))))
    (testing "for duplicate frames"
      ;; Index frames. For all frames flagged as :dup, the frame above it in
      ;; the stack (index i - 1) should be substantially the same source info.
      (let [ixd1 (zipmap (iterate inc 0) frames1)
            ixd2 (zipmap (iterate inc 0) frames2)
            dup? #(or (= (:name %1) (:name %2))
                      (and (= (:file %1) (:file %2))
                           (= (:line %1) (:line %2))))]
        (is (every? (fn [[i v]] (dup? v (get ixd1 (dec i))))
                    (filter (comp :dup :flags val) ixd1)))
        (is (every? (fn [[i v]] (dup? v (get ixd2 (dec i))))
                    (filter (comp :dup :flags val) ixd2)))))))

(deftest exception-causes-test
  (testing "Exception cause unrolling"
    (is (= 2 (count causes1)))
    (is (= 1 (count causes2))))
  (testing "Exception data"
    ;; If ex-data is present, the cause should have a :data attribute.
    (is (:data (first causes1)))
    (is (not (:data (first causes2))))))

(deftest ex-data-filtering-test
  (is (= {:a :b :c :d}
         (filtered-ex-data (ex-info "msg" {:a :b :c :d :repl-env :e})))))

(deftest cause-data-pretty-printing-test
  (testing "print-length"
    (is (= "{:a (0 1 2 ...)}\n"
           (:data (analyze-cause (ex-info "" {:a (range)}) (fn [object]
                                                             (binding [*print-length* 3]
                                                               (clojure.pprint/pprint object))))))))
  (testing "print-level"
    (is (= "{:a {#}}\n"
           (:data (analyze-cause (ex-info "" {:a {:b {:c {:d {:e nil}}}}}) (fn [object]
                                                                             (binding [*print-level* 3]
                                                                               (clojure.pprint/pprint object))))))))
  (testing "compilation errors"
    (let [clojure-version ((juxt :major :minor) *clojure-version*)]
      (if (< (compare clojure-version [1 10]) 0)
        ;; 1.8 / 1.9
        (is (re-find #"Unable to resolve symbol: not-defined in this context"
                     (:message (first causes3))))

        ;; 1.10+
        (is (re-find #"Syntax error compiling at \(cider/nrepl/middleware/stacktrace_test\.clj:"
                     (:message (first causes3))))))))

(deftest compilation-errors-test
  (testing "extract-location"
    (is (= {:class "clojure.lang.Compiler$CompilerException"
            :message "java.lang.RuntimeException: Unable to resolve symbol: foo in this context"
            :file "/foo/bar/baz.clj"
            :file-url nil
            :path "/foo/bar/baz.clj"
            :line 1
            :column 42}
           (extract-location {:class "clojure.lang.Compiler$CompilerException"
                              :message "java.lang.RuntimeException: Unable to resolve symbol: foo in this context, compiling:(/foo/bar/baz.clj:1:42)"})))

    (is (= {:class "clojure.lang.Compiler$CompilerException"
            :message "java.lang.NegativeArraySizeException"
            :file "/foo/bar/baz.clj"
            :file-url nil
            :path "/foo/bar/baz.clj"
            :line 1
            :column 42}
           (extract-location {:class "clojure.lang.Compiler$CompilerException"
                              :message "java.lang.NegativeArraySizeException, compiling:(/foo/bar/baz.clj:1:42)"}))))
  (testing "extract-location with location-data already present"
    (= {:class "clojure.lang.Compiler$CompilerException"
        :message "Syntax error macroexpanding clojure.core/let at (1:1)."
        :file nil
        :file-url nil
        :path "/foo/bar/baz.clj"
        :line 1
        :column 42}
       (extract-location {:class "clojure.lang.Compiler$CompilerException"
                          :location {:clojure.error/line 1
                                     :clojure.error/column 42
                                     :clojure.error/source "/foo/bar/baz.clj"
                                     :clojure.error/phase :macroexpand
                                     :clojure.error/symbol 'clojure.core/let}
                          :message "Syntax error macroexpanding clojure.core/let at (1:1)."}))))

(deftest analyze-cause-test
  (testing "check that location-data is returned"
    (let [e (ex-info "wat?" {:clojure.error/line 1
                             :clojure.error/column 42
                             :clojure.error/source "/foo/bar/baz.clj"
                             :clojure.error/phase :macroexpand
                             :clojure.error/symbol 'clojure.core/let})
          cause (analyze-cause e identity)]
      (is (= {:clojure.error/line 1
              :clojure.error/column 42
              :clojure.error/source "/foo/bar/baz.clj"
              :clojure.error/phase :macroexpand
              :clojure.error/symbol 'clojure.core/let}
             (:location cause))))))
