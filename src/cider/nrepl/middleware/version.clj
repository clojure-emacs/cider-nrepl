(ns cider.nrepl.middleware.version
  "Return version info of the CIDER-nREPL middleware itself."
  (:require [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [cider.nrepl.version :as version]
            [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]))

(defn- cider-version-reply
  "Returns CIDER-nREPL's version as a map which contains `:major`,
  `:minor`, `:incremental`, and `:qualifier` keys, just as
  `*clojure-version*` does."
  [msg]
  {:cider-version version/version})

(defn wrap-version
  "Middleware that provides CIDER-nREPL version information."
  [handler]
  (with-safe-transport handler
    "cider-version" cider-version-reply))

(set-descriptor!
 #'wrap-version
 {:describe-fn #'cider-version-reply ;; For the "describe" op. Merged in `:aux`
  :handles
  {"cider-version"
   {:doc "Returns the version of the CIDER-nREPL middleware."
    :requires {}
    :returns {"cider-version" "CIDER-nREPL's version map."
              "status" "done"}}}})
