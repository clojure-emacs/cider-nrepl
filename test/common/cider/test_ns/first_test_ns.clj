(ns cider.test-ns.first-test-ns)

(def some-test-var
  "This is a test var used to check eldoc returned for a variable."
  1)

(defn same-name-testing-function
  "Multiple vars with the same name in different ns's. Used to test ns-list-vars-by-name."
  []
  true)
