(ns cider.nrepl.middleware.apropos
  "Search symbols and docs matching a regular expression"
  {:author "Jeff Valk"}
  (:require [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [orchard.apropos :as apropos]))

;;; ## Middleware

(defn apropos [msg]
  {:apropos-matches (apropos/find-symbols msg)})

(defn handle-apropos [handler msg]
  (with-safe-transport handler msg
    "apropos" apropos))
