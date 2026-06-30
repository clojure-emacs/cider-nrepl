(ns cider.nrepl.descriptor-test
  "Regression tests for the op descriptors declared in `cider.nrepl`."
  (:require
   [cider.nrepl]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(defn- op-descriptor
  "The descriptor for `op` as declared by the given `wrapper` var."
  [wrapper op]
  (-> (meta wrapper)
      :nrepl.middleware/descriptor
      (get-in [:handles op])))

(defn- all-descriptors
  "The descriptor of every middleware wrapper in `cider.nrepl`."
  []
  (->> (ns-interns 'cider.nrepl)
       vals
       (keep #(:nrepl.middleware/descriptor (meta %)))))

(defn- all-handles
  "The merged `:handles` maps of every middleware wrapper in `cider.nrepl`."
  []
  (apply merge (map :handles (all-descriptors))))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(deftest every-op-documents-a-doc-test
  ;; A blank :doc renders an empty entry in the generated op reference.
  (doseq [[op desc] (all-handles)]
    (testing op
      (is (non-blank-string? (:doc desc))
          (str op " is missing a non-blank :doc")))))

(deftest param-and-return-docs-are-well-formed-test
  ;; Every :returns/:requires/:optional entry maps a string key to a non-blank
  ;; string - the shape clients render. Guards the blank/garbled docs #993 fixed.
  (doseq [[op desc] (all-handles)
          slot [:returns :requires :optional]
          :let [m (get desc slot)]
          :when m]
    (testing (str op " " slot)
      (is (map? m))
      (doseq [[k v] m]
        (is (string? k) (str op " " slot " has a non-string key " (pr-str k)))
        (is (non-blank-string? v)
            (str op " " slot " entry " (pr-str k) " has a blank description"))))))

(deftest op-names-are-unique-test
  ;; Two middleware claiming the same op would make dispatch ambiguous.
  (let [dups (->> (all-descriptors)
                  (mapcat (comp keys :handles))
                  frequencies
                  (keep (fn [[op n]] (when (> n 1) op))))]
    (is (empty? dups) (str "duplicate op names across middleware: " (pr-str dups)))))

(deftest get-state-descriptor-test
  ;; `cider/get-state` used to carry an empty descriptor with its `:returns`
  ;; misplaced at the wrapper level, so the op rendered blank in the docs.
  (let [op (op-descriptor #'cider.nrepl/wrap-tracker "cider/get-state")]
    (is (seq (:doc op)))
    (is (contains? (:returns op) "repl-type"))
    (is (contains? (:returns op) "changed-namespaces"))))

(deftest log-remove-consumer-returns-test
  ;; The `:returns` key was copy-pasted from the add-consumer op.
  (let [op (op-descriptor #'cider.nrepl/wrap-log "cider/log-remove-consumer")]
    (is (contains? (:returns op) "cider/log-remove-consumer"))
    (is (not (contains? (:returns op) "cider/log-add-consumer")))))

(deftest deprecated-aliases-mirror-their-primary-test
  ;; Every unnamespaced op whose doc follows the standard "use `cider/x` instead"
  ;; form must be a faithful mirror of its `cider/`-namespaced primary: same
  ;; params/returns, and a doc that's just the primary's with the notice
  ;; prepended. This guards against drift and underpins `with-deprecated-aliases`
  ;; (ops deprecated in favour of a *different* op use the "[DEPRECATED ...]"
  ;; form and are intentionally excluded).
  (let [handles (all-handles)
        pairs (for [[op desc] handles
                    :let [[_ primary] (re-matches #"Deprecated: use `(cider/[^`]+)` instead\. .*"
                                                  (str (:doc desc)))]
                    :when (and primary (not (str/starts-with? op "cider/")))]
                [op primary])]
    (is (seq pairs) "there are deprecated aliases to check")
    (doseq [[bare primary] pairs
            :let [bare-desc (get handles bare)
                  primary-desc (get handles primary)]]
      (testing (str bare " mirrors " primary)
        (is (some? primary-desc) (str "primary op " primary " exists"))
        (is (= (:requires primary-desc) (:requires bare-desc)))
        (is (= (:optional primary-desc) (:optional bare-desc)))
        (is (= (:returns primary-desc) (:returns bare-desc)))
        (is (= (str "Deprecated: use `" primary "` instead. " (:doc primary-desc))
               (:doc bare-desc)))))))
