(ns cider.nrepl.descriptor-test
  "Regression tests for the op descriptors declared in `cider.nrepl`."
  (:require
   [cider.nrepl]
   [clojure.test :refer :all]))

(defn- op-descriptor
  "The descriptor for `op` as declared by the given `wrapper` var."
  [wrapper op]
  (-> (meta wrapper)
      :nrepl.middleware/descriptor
      (get-in [:handles op])))

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
