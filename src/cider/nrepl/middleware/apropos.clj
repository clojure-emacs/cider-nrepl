(ns cider.nrepl.middleware.apropos
  "Search symbols and docs matching a regular expression"
  {:author "Jeff Valk"}
  (:require
   [cider.nrepl.middleware.util.coerce :as util.coerce]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [orchard.apropos :as apropos]))

;;; ## Middleware

(defn apropos [msg]
  {:apropos-matches
   (apropos/find-symbols
    (cond-> msg
      ;; Compatibility for the pre-var-query API
      (:privates? msg)
      (assoc-in [:var-query :private?] true)

      (:query msg)
      (assoc-in [:var-query :search] (:query msg))

      (not (:case-sensitive? msg))
      (update-in [:var-query :search] #(format "(?i:%s)" %))

      (:docs? msg)
      (assoc-in [:var-query :search-property] :doc)

      (:docs? msg)
      (assoc :full-doc? true)

      true
      (update :var-query util.coerce/var-query)

      (:ns msg)
      (update :ns (comp find-ns symbol))))})

(defn handle-apropos [handler msg]
  (with-safe-transport handler msg
    "apropos" apropos))
