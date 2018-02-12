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

(disable-warning
 {:linter :unused-ret-vals
  :if-inside-macroexpansion-of #{'boot.core/deftask}
  :within-depth 7
  :reason "The `deftask` macro often includes calls to `boot.util/dbug*` whose purpose is solely for side-effects."})
