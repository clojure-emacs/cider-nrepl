(disable-warning
 {:linter :constant-test
  :if-inside-macroexpansion-of #{'clojure.test/is}
  :within-depth 1
  :reason "The `is` macro commonly expands to contain an `if` with a condition that is a constant."})

(disable-warning
 {:linter :constant-test
  :if-inside-macroexpansion-of #{'debugger.core/break}
  :within-depth 7
  :reason "The `break` macro commonly expands to contain an `if` with a condition that is a constant."})

(disable-warning
 {:linter :constant-test
  :if-inside-macroexpansion-of #{'clojure.core/cond->}
  :within-depth 2
  :reason "The `cond->` macro can legitimately contain always-true predicates."})
