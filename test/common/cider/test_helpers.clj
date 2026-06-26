(ns cider.test-helpers
  (:require [clojure.test :refer :all]
            ;; `match?` is injected unqualified into `is+` expansions (via
            ;; `~'match?`), so clj-kondo can't see it used here, but the require
            ;; is still needed to register match?'s `clojure.test/assert-expr`.
            #_{:clj-kondo/ignore [:unused-namespace :unused-referred-var]}
            [matcher-combinators.test :refer [match?]]))

(defmacro is+
  "Like `is` but wraps expected value in matcher-combinators's `match?`."
  ([expected actual]
   `(is+ ~expected ~actual nil))
  ([expected actual message]
   `(is (~'match? ~expected ~actual) ~message)))

(defn mc-includes [expected]
  #(and (string? %) (.contains ^String % expected)))
