(ns cider.nrepl.middleware.debug-integration-test.fn
  "Function for debug integration test.

  NOTE: if modifying this file, modify corresponding tests in
  debug_integration_test.clj.")

(defn as-sym
  [x]
  (cond
    (symbol? x) x
    (string? x) (if-let [[_ ns sym] (re-matches #"(.+)/(.+)" x)]
                  (symbol ns sym)
                  (symbol x))))
