(ns cider.test-helpers
  (:require [clojure.test :refer :all]
            [matcher-combinators.matchers :as matchers]
            [matcher-combinators.test :refer [match?]]))

(defmacro is+
  "Like `is` but wraps expected value in matcher-combinators's `match?`."
  [expected actual & [message]]
  `(is (~'match? ~expected ~actual) ~@(when message [message])))
